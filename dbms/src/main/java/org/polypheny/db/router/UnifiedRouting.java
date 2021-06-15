/*
 * Copyright 2019-2021 The Polypheny Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.polypheny.db.router;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.math3.stat.StatUtils;
import org.polypheny.db.adapter.AdapterManager;
import org.polypheny.db.adapter.DataStore;
import org.polypheny.db.catalog.Catalog;
import org.polypheny.db.catalog.entity.CatalogColumnPlacement;
import org.polypheny.db.catalog.entity.CatalogTable;
import org.polypheny.db.config.ConfigBoolean;
import org.polypheny.db.config.ConfigEnum;
import org.polypheny.db.config.ConfigInteger;
import org.polypheny.db.config.ConfigManager;
import org.polypheny.db.config.WebUiGroup;
import org.polypheny.db.information.InformationGroup;
import org.polypheny.db.information.InformationHtml;
import org.polypheny.db.information.InformationManager;
import org.polypheny.db.information.InformationPage;
import org.polypheny.db.information.InformationTable;
import org.polypheny.db.monitoring.core.MonitoringServiceProvider;
import org.polypheny.db.monitoring.events.RoutingEvent;
import org.polypheny.db.monitoring.events.metrics.RoutingDataPoint;
import org.polypheny.db.processing.QueryParameterizer;
import org.polypheny.db.rel.RelNode;
import org.polypheny.db.rel.RelRoot;
import org.polypheny.db.rel.RelShuttleImpl;
import org.polypheny.db.rel.core.TableFunctionScan;
import org.polypheny.db.rel.core.TableScan;
import org.polypheny.db.rel.logical.LogicalAggregate;
import org.polypheny.db.rel.logical.LogicalCorrelate;
import org.polypheny.db.rel.logical.LogicalExchange;
import org.polypheny.db.rel.logical.LogicalFilter;
import org.polypheny.db.rel.logical.LogicalIntersect;
import org.polypheny.db.rel.logical.LogicalJoin;
import org.polypheny.db.rel.logical.LogicalMatch;
import org.polypheny.db.rel.logical.LogicalMinus;
import org.polypheny.db.rel.logical.LogicalProject;
import org.polypheny.db.rel.logical.LogicalSort;
import org.polypheny.db.rel.logical.LogicalTableModify;
import org.polypheny.db.rel.logical.LogicalUnion;
import org.polypheny.db.rel.logical.LogicalValues;
import org.polypheny.db.routing.ExecutionTimeMonitor.ExecutionTimeObserver;
import org.polypheny.db.routing.Router;
import org.polypheny.db.transaction.Statement;
import org.polypheny.db.util.background.BackgroundTask.TaskPriority;
import org.polypheny.db.util.background.BackgroundTask.TaskSchedulingType;
import org.polypheny.db.util.background.BackgroundTaskManager;

@Slf4j
public class UnifiedRouting extends AbstractRouter {

    private static final ConfigBoolean TRAINING = new ConfigBoolean(
            "unifiedRouting/training",
            "Whether routing table should be adjusted according to the measured execution times. Setting this to false keeps the routing table in its current state.",
            true );
    private static final ConfigInteger WINDOW_SIZE = new ConfigInteger(
            "unifiedRouting/windowSize",
            "Size of the moving average on the execution times per query class used for calculating the routing table.",
            25 );
    private static final ConfigInteger SHORT_RUNNING_SIMILAR_THRESHOLD = new ConfigInteger(
            "unifiedRouting/shortRunningSimilarThreshold",
            "The amount of time (specified as percentage of the fastest time) an adapter can be slower than the fastest adapter in order to be still considered for executing queries of a certain query class. Setting this to zero results in only considering the fastest adapter.",
            0 );
    private static final ConfigInteger LONG_RUNNING_SIMILAR_THRESHOLD = new ConfigInteger(
            "unifiedRouting/longRunningSimilarThreshold",
            "The amount of time (specified as percentage of the fastest time) an adapter can be slower than the fastest adapter in order to be still considered for executing queries of a certain query class. Setting this to zero results in only considering the fastest adapter.",
            0 );
    private static final ConfigInteger SHORT_RUNNING_LONG_RUNNING_THRESHOLD = new ConfigInteger(
            "unifiedRouting/shortRunningLongRunningThreshold",
            "The minimal execution time (in milliseconds) for a query to be considered as long-running. Queries with lower execution times are considered as short-running.",
            1000 );

    private static final ConfigEnum QUERY_CLASS_PROVIDER = new ConfigEnum(
            "unifiedRouting/queryClassProvider",
            "Which implementation to use for deriving the query class from a query plan.",
            QUERY_CLASS_PROVIDER_METHOD.class,
            QUERY_CLASS_PROVIDER_METHOD.QUERY_PARAMETERIZER );


    private enum QUERY_CLASS_PROVIDER_METHOD {ICARUS_SHUTTLE, QUERY_PARAMETERIZER}


    private static final UnifiedRoutingTable routingTable = new UnifiedRoutingTable();

    private Set<Integer> selectedAdapterIds = Sets.newHashSet(-2); // Is set in analyze
    private String queryClassString;


    private UnifiedRouting() {
        // Intentionally left empty
        //MonitoringServiceProvider.getInstance()
    }

    @Override
    protected void analyze( Statement statement, RelRoot logicalRoot ) {
        if ( !(logicalRoot.rel instanceof LogicalTableModify) ) {
            if ( QUERY_CLASS_PROVIDER.getEnum() == QUERY_CLASS_PROVIDER_METHOD.ICARUS_SHUTTLE ) {
                IcarusShuttle icarusShuttle = new IcarusShuttle();
                logicalRoot.rel.accept( icarusShuttle );
                queryClassString = icarusShuttle.hashBasis.toString();
            } else if ( QUERY_CLASS_PROVIDER.getEnum() == QUERY_CLASS_PROVIDER_METHOD.QUERY_PARAMETERIZER ) {
                QueryParameterizer parameterizer = new QueryParameterizer( 0, new LinkedList<>() );
                RelNode parameterized = logicalRoot.rel.accept( parameterizer );
                queryClassString = parameterized.relCompareString();
            } else {
                throw new RuntimeException( "Unknown value for QUERY_CLASS_PROVIDER config: " + QUERY_CLASS_PROVIDER.getEnum().name() );
            }

            if ( routingTable.contains( queryClassString ) && routingTable.get( queryClassString ).size() > 0 ) {
                selectedAdapterIds.clear();
                selectedAdapterIds = routeQuery( routingTable.get( queryClassString ) ) ;


                // In case the query class is known but the table has been dropped and than recreated with the same name,
                // the query class is known but only contains information for the adapters with no placement. To handle this
                // special case (selectedAdapterId == -2) we have to set it to -1.
                // TODO: where will it be set to -2?, need to handle it in other way
                if ( statement.getTransaction().isAnalyze() ) {
                    InformationGroup group = new InformationGroup( page, "Routing Table Entry" );
                    statement.getTransaction().getQueryAnalyzer().addGroup( group );
                    InformationTable table = new InformationTable( group, ImmutableList.copyOf( routingTable.knownAdapters.values() ) );
                    Map<Set<Integer>, Integer> entry = routingTable.get( queryClassString );

                    // TODO: get from Monitoring
                    Map<Set<Integer>, List<Double>> timesEntry = routingTable.getExecutionTimes( queryClassString );


                    List<String> row1 = new LinkedList<>();
                    List<String> row2 = new LinkedList<>();
                    for ( Entry<Set<Integer>, Integer> e : entry.entrySet() ) {
                        if ( e.getValue() == UnifiedRoutingTable.MISSING_VALUE ) {
                            row1.add( "MISSING VALUE" );
                            row2.add( "" );
                        } else if ( e.getValue() == UnifiedRoutingTable.NO_PLACEMENT ) {
                            row1.add( "NO PLACEMENT" );
                            row2.add( "" );
                        } else {
                            row1.add( e.getValue() + "" );
                            double mean = StatUtils.mean(
                                    timesEntry.get( e.getKey() ).stream().mapToDouble( d -> d ).toArray(),
                                    0,
                                    timesEntry.get( e.getKey() ).size() );
                            row2.add( mean / 1000000.0 + " ms" );
                        }
                    }
                    table.addRow( row1 );
                    table.addRow( row2 );
                    statement.getTransaction().getQueryAnalyzer().registerInformation( table );
                }
            } else {
                if ( statement.getTransaction().isAnalyze() ) {
                    InformationGroup group = new InformationGroup( page, "Routing Table Entry" );
                    statement.getTransaction().getQueryAnalyzer().addGroup( group );
                    InformationHtml html = new InformationHtml( group, "Unknown query class" );
                    statement.getTransaction().getQueryAnalyzer().registerInformation( html );
                }
                selectedAdapterIds.clear();
            }
        }
        if ( statement.getTransaction().isAnalyze() ) {
            InformationGroup group = new InformationGroup( page, "Unified Routing" );
            statement.getTransaction().getQueryAnalyzer().addGroup( group );
            InformationHtml informationHtml = new InformationHtml(
                    group,
                    "<p><b>Selected Store ID:</b> " + selectedAdapterIds + "</p>"
                            + "<p><b>Query Class:</b> " + queryClassString + "</p>" );
            statement.getTransaction().getQueryAnalyzer().registerInformation( informationHtml );
        }
    }


    private Set<Integer> routeQuery( Map<Set<Integer>, Integer> routingTableRow ) {
        // Check if there is an adapter for which we do not have an execution time
        for ( Entry<Set<Integer>, Integer> entry : routingTable.get( queryClassString ).entrySet() ) {
            if ( entry.getValue() == UnifiedRoutingTable.MISSING_VALUE ) {
                // We have no execution time for this adapter.
                return entry.getKey();
            }
        }

        if ( SHORT_RUNNING_SIMILAR_THRESHOLD.getInt() == 0 ) {
            // There should only be exactly one entry in the routing table > 0
            for ( Entry<Set<Integer>, Integer> entry : routingTable.get( queryClassString ).entrySet() ) {
                if ( entry.getValue() == 100 ) {
                    // We have no execution time for this adapter.
                    return entry.getKey();
                }
            }
        } else {
            int p = 0;
            int random = Math.min( (int) (Math.random() * 100) + 1, 100 );
            for ( Map.Entry<Set<Integer>, Integer> entry : routingTableRow.entrySet() ) {
                p += Math.max( entry.getValue(), 0 ); // avoid subtracting -2
                if ( p >= random ) {
                    return entry.getKey();
                }
            }
        }
        throw new RuntimeException( "Something went wrong..." );
    }


    @Override
    protected void wrapUp( Statement statement, RelNode routed ) {
        if ( TRAINING.getBoolean() ) {
            executionTimeMonitor.subscribe( routingTable, selectedAdapterIds + "-" + queryClassString );
        }
        if ( statement.getTransaction().isAnalyze() ) {
            InformationGroup executionTimeGroup = new InformationGroup( page, "Execution Time" );
            statement.getTransaction().getQueryAnalyzer().addGroup( executionTimeGroup );
            executionTimeMonitor.subscribe(
                    ( reference, nanoTime ) -> {
                        InformationHtml html = new InformationHtml( executionTimeGroup, nanoTime / 1000000.0 + " ms" );
                        statement.getTransaction().getQueryAnalyzer().registerInformation( html );
                    },
                    selectedAdapterIds + "-" + queryClassString );
        }
    }


    // Execute the table scan on the adapter selected in the analysis (in Icarus routing all tables are expected to be
    // replicated to all adapters)
    //
    // Icarus routing is based on a full replication of data to all underlying adapters (data stores). This implementation
    // therefore assumes that there is either no placement of a table on a adapter or a full placement.
    //
    @Override
    protected List<CatalogColumnPlacement> selectPlacement( RelNode node, CatalogTable table ) {
        // Update known adapters
        // updateKnownAdapters( table.placementsByAdapter.keySet() );
        val placements = updateKnownAdapters( table );

        if ( selectedAdapterIds.size() == 0  ) {
            routingTable.initializeRow( queryClassString, placements );
            selectedAdapterIds = placements.get( 0 );
        }
        if ( selectedAdapterIds.size() > 0) {

            val allPlacements = selectedAdapterIds
                    .stream()
                    .map( id ->  Catalog.getInstance().getColumnPlacementsOnAdapter( id, table.id ) )
                    .flatMap( List::stream )
                    .collect( Collectors.toList());

            List<CatalogColumnPlacement> result = new LinkedList<>();
            for ( val colId : table.columnIds) {
                val firstPlacement = allPlacements
                        .stream()
                        .filter( place -> place.columnId == colId )
                        .findFirst();
                if(firstPlacement.isPresent()){
                    result.add( firstPlacement.get() );
                }
            }


            if(result.size() == 0){
                throw new RuntimeException( "The previously selected store does not contain a placement of this table. Store ID: " + selectedAdapterIds );
            }

            return result;
        }
        throw new RuntimeException( "The previously selected store does not contain a placement of this table. Store ID: " + selectedAdapterIds );
    }

    public List<HashSet<Integer>> updateKnownAdapters( CatalogTable table ) {

        // get adapter full placements
        val adapterWithFullPlacement = table.placementsByAdapter.entrySet()
                .stream()
                .filter( elem -> elem.getValue().size() == table.columnIds.size() )
                .map( adapter -> Sets.newHashSet(adapter.getKey()) ) // adapterId
                .collect( Collectors.toList());

        if(adapterWithFullPlacement.size() == 0){
            int adapterIdWithMostPlacements = -1;
            int numOfPlacements = 0;
            for ( Entry<Integer, ImmutableList<Long>> entry : table.placementsByAdapter.entrySet() ) {
                if ( entry.getValue().size() > numOfPlacements ) {
                    adapterIdWithMostPlacements = entry.getKey();
                    numOfPlacements = entry.getValue().size();
                }
            }

            // get combined adapters for query, non available with full placements
            val adapterIds = new HashSet<Integer>();
            for ( long cid : table.columnIds ) {
                if ( table.placementsByAdapter.get( adapterIdWithMostPlacements ).contains( cid ) ) {
                    adapterIds.add( Catalog.getInstance().getColumnPlacement( adapterIdWithMostPlacements, cid ).adapterId );
                } else {
                    adapterIds.add( Catalog.getInstance().getColumnPlacements( cid ).get( 0 ).adapterId );
                }
            }

            adapterWithFullPlacement.add( adapterIds );

        }

        for ( val placement : adapterWithFullPlacement) {
            if ( !routingTable.knownAdapters.containsKey( placement ) ) {
                val uniqueName = placement.stream()
                        .map( adapterId -> Catalog.getInstance().getAdapter( adapterId ).uniqueName )
                        .collect( Collectors.joining(","));

                routingTable.knownAdapters.put( placement, uniqueName );
                if ( routingTable.routingTable.get( queryClassString ) != null && !routingTable.routingTable.get( queryClassString ).containsKey( placement ) ) {
                    routingTable.routingTable.get( queryClassString ).put( placement, UnifiedRoutingTable.MISSING_VALUE );
                }
            }
        }

        return adapterWithFullPlacement;
    }


    // Create table on all data stores (not on data sources)
    @Override
    public List<DataStore> createTable( long schemaId, Statement statement ) {
        Map<String, DataStore> availableStores = AdapterManager.getInstance().getStores();
        List<DataStore> result = new LinkedList<>( availableStores.values() );
        if ( result.size() == 0 ) {
            throw new RuntimeException( "No suitable data store found" );
        }
        return ImmutableList.copyOf( result );
    }


    @Override
    public List<DataStore> addColumn( CatalogTable catalogTable, Statement statement ) {
        List<DataStore> result = new LinkedList<>();
        for ( int storeId : catalogTable.placementsByAdapter.keySet() ) {
            result.add( AdapterManager.getInstance().getStore( storeId ) );
        }
        if ( result.size() == 0 ) {
            throw new RuntimeException( "No suitable data store found" );
        }
        return ImmutableList.copyOf( result );
    }


    @Override
    public void dropPlacements( List<CatalogColumnPlacement> placements ) {
        routingTable.dropPlacements( placements );
    }


    private static class UnifiedRoutingTable implements ExecutionTimeObserver {

        public static final int MISSING_VALUE = -1;
        public static final int NO_PLACEMENT = -2;

        private final Map<String, Map<Set<Integer>, Integer>> routingTable = new ConcurrentHashMap<>();  // QueryClassStr -> (Adapter -> Percentage)
        private final Map<Set<Integer>, String> knownAdapters = new HashMap<>(); // Adapter Id -> Adapter Name
        //private final Map<String, Map<Set<Integer>, CircularFifoQueue<Double>>> times = new ConcurrentHashMap<>();  // QueryClassStr -> (Adapter -> Time)

        private final Lock processingQueueLock = new ReentrantLock();


        private UnifiedRoutingTable() {
            // Information
            InformationManager im = InformationManager.getInstance();
            InformationPage page = new InformationPage( "Unified Routing" );
            page.fullWidth();
            im.addPage( page );
            // Routing table
            InformationGroup routingTableGroup = new InformationGroup( page, "Routing Table" );
            im.addGroup( routingTableGroup );
            InformationTable routingTableElement = new InformationTable(
                    routingTableGroup,
                    Arrays.asList( "Query Class" ) );
            im.registerInformation( routingTableElement );

            // update
            page.setRefreshFunction( () -> {
                // Update labels
                if ( routingTable.size() > 0 ) {
                    LinkedList<String> labels = new LinkedList<>();
                    labels.add( "Query Class" );
                    labels.addAll( knownAdapters.values() );
                    routingTableElement.updateLabels( labels );
                }
                // Update rows
                routingTableElement.reset();
                routingTable.forEach( ( k, v ) -> {
                    List<String> row = new LinkedList<>();
                    row.add( k );
                    for ( Integer integer : v.values() ) {
                        if ( integer == UnifiedRoutingTable.MISSING_VALUE ) {
                            row.add( "Unknown" );
                        } else if ( integer == UnifiedRoutingTable.NO_PLACEMENT ) {
                            row.add( "-" );
                        } else {
                            row.add( integer + "" );
                        }
                    }
                    routingTableElement.addRow( row );
                } );
            } );

            // Background Task
            BackgroundTaskManager.INSTANCE.registerTask(
                    this::process,
                    "Process query execution times and update Icarus routing table",
                    TaskPriority.LOW,
                    TaskSchedulingType.EVERY_FIVE_SECONDS
            );
        }


        public boolean contains( String queryClassStr ) {
            return routingTable.containsKey( queryClassStr );
        }


        public Map<Set<Integer>, Integer> get( String queryClassStr ) {
            return routingTable.get( queryClassStr );
        }

        public Map<Set<Integer>, List<Double>> getExecutionTimes(String queryClassString){
            val points = MonitoringServiceProvider.getInstance().getRoutingDataPoints( queryClassString );

            val mapped = points.stream()
                    .map( elem -> (RoutingDataPoint)elem ).collect( Collectors.toList());

            val filtered = mapped.stream()
                    .filter( elem -> elem.getQueryClassString().equals( queryClassString ) )
                    .collect( Collectors.toList());

            if(filtered.size() == 0){
                return Collections.emptyMap();
            }

            val group =   filtered.stream()
                    .collect(
                            Collectors.groupingBy(
                                    elem -> elem.getAdapterId() ,
                                    Collectors.mapping(elem -> elem.getNanoTime(), Collectors.toList())
                            )
                    );

            return group;
        }


        private void process() {
            processingQueueLock.lock();

            // Update routing table
            // get values from monitoring
            for ( String queryClass : routingTable.keySet() ) {
                Map<Set<Integer>, Double> meanTimeRow = new HashMap<>();

                // TODO: get from Monitoring
                for ( Map.Entry<Set<Integer>, List<Double>> entry : this.getExecutionTimes( queryClass ).entrySet() ) {
                    double mean = StatUtils.mean(
                            entry.getValue().stream().mapToDouble( d -> d ).toArray(),
                            0,
                            entry.getValue().size() );
                    meanTimeRow.put( entry.getKey(), mean );
                }

                Map<Set<Integer>, Integer> newRow = new HashMap<>();
                for ( Set<Integer> adapterIds : knownAdapters.keySet() ) {
                    newRow.put( adapterIds, UnifiedRoutingTable.NO_PLACEMENT );

                }
                Map<Set<Integer>, Integer> calculatedRow = generateRow( meanTimeRow );
                for ( Map.Entry<Set<Integer>, Integer> oldEntry : routingTable.get( queryClass ).entrySet() ) {
                    if ( oldEntry.getValue() == NO_PLACEMENT ) {
                        newRow.put( oldEntry.getKey(), NO_PLACEMENT );
                    } else if ( calculatedRow.containsKey( oldEntry.getKey() ) ) {
                        newRow.replace( oldEntry.getKey(), calculatedRow.get( oldEntry.getKey() ) );
                    } else {
                        newRow.replace( oldEntry.getKey(), MISSING_VALUE );
                    }
                }
                routingTable.replace( queryClass, newRow );
            }
            processingQueueLock.unlock();
        }

        // called by execution monitor to inform about execution time
        @Override
        public void executionTime( String reference, long nanoTime ) {
            String adapterIdStr = reference.split( "-" )[0]; // Reference starts with "ADAPTER_ID-..."
            if ( adapterIdStr.equals( "" ) ) {
                // No adapterIdStr string. This happens if a query contains no table (e.g. select 1 )
                return;
            }

            adapterIdStr = adapterIdStr.substring( 1, adapterIdStr.length() - 1 );
            val adapters = Arrays.stream( adapterIdStr.split( "," ) )
                    .map( value -> Integer.parseInt( value.trim() ) )
                    .collect( Collectors.toSet());

            String queryClassString = reference.substring( adapterIdStr.length() + 3 );
            MonitoringServiceProvider.getInstance().monitorEvent( new RoutingEvent(queryClassString, adapters, nanoTime) );
        }


        public void dropPlacements( List<CatalogColumnPlacement> placements ) {
            process();// empty processing queue
            processingQueueLock.lock();
            for ( CatalogColumnPlacement placement : placements ) {
                knownAdapters.remove( placement.adapterId );
                for ( Map<Set<Integer>, Integer> entry : routingTable.values() ) {
                    entry.remove( placement.adapterId );
                }
            }
            processingQueueLock.unlock();
            process();// update routing table
        }


        public void initializeRow( String queryClassString, List<HashSet<Integer>> adapterPlacements ) {
            Map<Set<Integer>, Integer> row = new HashMap<>();
            // Initialize with NO_PLACEMENT
            for ( val adapterIds : knownAdapters.keySet() ) {
                row.put( adapterIds, NO_PLACEMENT );
            }
            // Set missing values entry
            for(val adapters : adapterPlacements){
                row.replace( adapters, MISSING_VALUE );
            }

            routingTable.put( queryClassString, row );
        }


        protected Map<Set<Integer>, Integer> generateRow( Map<Set<Integer>, Double> map ) {
            Map<Set<Integer>, Integer> row;
            // find fastest
            Set<Integer> fastestAdapterIds = new HashSet<>();
            double fastestTime = Double.MAX_VALUE;
            for ( Map.Entry<Set<Integer>, Double> entry : map.entrySet() ) {
                if ( fastestTime > entry.getValue() ) {
                    fastestAdapterIds = entry.getKey();
                    fastestTime = entry.getValue();
                }
            }
            long shortRunningLongRunningThreshold = SHORT_RUNNING_LONG_RUNNING_THRESHOLD.getInt() * 1_000_000L; // multiply with 1000000 to get nanoseconds
            if ( fastestTime < shortRunningLongRunningThreshold && SHORT_RUNNING_SIMILAR_THRESHOLD.getInt() != 0 ) {
                row = calc( map, SHORT_RUNNING_SIMILAR_THRESHOLD.getInt(), fastestTime, fastestAdapterIds );
            } else if ( fastestTime > shortRunningLongRunningThreshold && LONG_RUNNING_SIMILAR_THRESHOLD.getInt() != 0 ) {
                row = calc( map, LONG_RUNNING_SIMILAR_THRESHOLD.getInt(), fastestTime, fastestAdapterIds );
            } else {
                row = new HashMap<>();
                // init row with 0
                for ( val adapterIds : map.keySet() ) {
                    row.put( adapterIds, 0 );
                }
                if ( fastestAdapterIds.size() != 0 && fastestTime > 0 ) {
                    row.put( fastestAdapterIds, 100 );
                }
            }
            return row;
        }


        private Map<Set<Integer>, Integer> calc( Map<Set<Integer>, Double> map, int similarThreshold, double fastestTime, Set<Integer> fastestStore ) {
            HashMap<Set<Integer>, Integer> row = new HashMap<>();
            ArrayList<Integer> percents = new ArrayList<>();
            Map<Set<Integer>, Double> stores = new HashMap<>();
            // init row with 0
            for ( val mapEntry : map.keySet() ) {
                row.put(mapEntry, 0);
            }


            // calc 100%
            int threshold = (int) (fastestTime + (fastestTime * (similarThreshold / 100.0)));
            int hundredPercent = 0;
            for ( Map.Entry<Set<Integer>, Double> entry : map.entrySet() ) {
                if ( threshold >= entry.getValue() ) {
                    hundredPercent += entry.getValue();
                }
            }
            // calc percents
            double onePercent = hundredPercent / 100.0;
            for ( Map.Entry<Set<Integer>, Double> entry : map.entrySet() ) {
                if ( threshold >= entry.getValue() ) {
                    double d = entry.getValue().intValue() / onePercent;
                    int t = Math.min( (int) d, 100 ); // This is not nice... But if there is only one entry with 100 percent, it some time happens that we get 101
                    percents.add( t );
                    stores.put( entry.getKey(), entry.getValue() );
                }
            }
            // add
            Collections.sort( percents );
            Collections.reverse( percents );
            for ( Map.Entry<Set<Integer>, Double> entry : entriesSortedByValues( stores ) ) {
                row.put( entry.getKey(), percents.remove( 0 ) );
            }
            // normalize to 100
            int sum = 0;
            for ( Map.Entry<Set<Integer>, Integer> entry : row.entrySet() ) {
                sum += entry.getValue();
            }
            if ( sum == 0 ) {
                log.error( "Routing table row is empty! This should not happen!" );
            } else if ( sum > 100 ) {
                log.error( "Routing table row does sum up to a value greater 100! This should not happen! The value is: " + sum + " | Entries: " + row.values().toString() );
            } else if ( sum < 100 ) {
                if ( fastestStore.size() == 0 ) {
                    log.error( "Fastest Store is -1! This should not happen!" );
                } else if ( !row.containsKey( fastestStore ) ) {
                    log.error( "Row does not contain the fastest row! This should not happen!" );
                } else {
                    int delta = 100 - sum;
                    row.replace( fastestStore, row.get( fastestStore ) + delta );
                }
            }
            return row;
        }


        //http://stackoverflow.com/a/2864923
        private static <K, V extends Comparable<? super V>> SortedSet<Entry<K, V>> entriesSortedByValues( Map<K, V> map ) {
            SortedSet<Map.Entry<K, V>> sortedEntries = new TreeSet<>( ( e1, e2 ) -> {
                int res = e1.getValue().compareTo( e2.getValue() );
                return res != 0 ? res : 1;
            } );
            sortedEntries.addAll( map.entrySet() );
            return sortedEntries;
        }
    }

    public static class UnifiedRouterFactory extends RouterFactory {

        public UnifiedRouterFactory() {
            super();
            final ConfigManager configManager = ConfigManager.getInstance();
            // Only initialize ones
            if ( configManager.getConfig( TRAINING.getKey() ) == null ) {
                final WebUiGroup unifiedGroupGroup = new WebUiGroup( "unifiedGroup", RouterManager.getInstance().routingPage.getId(), 2 );
                unifiedGroupGroup.withTitle( "Unified Routing" );
                configManager.registerWebUiGroup( unifiedGroupGroup );

                configManager.registerConfig( TRAINING );
                TRAINING.withUi( unifiedGroupGroup.getId() );

                configManager.registerConfig( WINDOW_SIZE );
                WINDOW_SIZE.withUi( unifiedGroupGroup.getId() );

                configManager.registerConfig( SHORT_RUNNING_SIMILAR_THRESHOLD );
                SHORT_RUNNING_SIMILAR_THRESHOLD.withUi( unifiedGroupGroup.getId() );

                configManager.registerConfig( LONG_RUNNING_SIMILAR_THRESHOLD );
                LONG_RUNNING_SIMILAR_THRESHOLD.withUi( unifiedGroupGroup.getId() );

                configManager.registerConfig( SHORT_RUNNING_LONG_RUNNING_THRESHOLD );
                SHORT_RUNNING_LONG_RUNNING_THRESHOLD.withUi( unifiedGroupGroup.getId() );

                configManager.registerConfig( QUERY_CLASS_PROVIDER );
                QUERY_CLASS_PROVIDER.withUi( unifiedGroupGroup.getId() );
            }
        }


        @Override
        public Router createInstance() {
            return new UnifiedRouting();
        }

    }


    // TODO MV: This should be improved to include more information on the used tables and columns
    private static class IcarusShuttle extends RelShuttleImpl {

        private final HashSet<String> hashBasis = new HashSet<>();


        @Override
        public RelNode visit( LogicalAggregate aggregate ) {
            hashBasis.add( "LogicalAggregate#" + aggregate.getAggCallList() );
            return visitChild( aggregate, 0, aggregate.getInput() );
        }


        @Override
        public RelNode visit( LogicalMatch match ) {
            hashBasis.add( "LogicalMatch#" + match.getTable().getQualifiedName() );
            return visitChild( match, 0, match.getInput() );
        }


        @Override
        public RelNode visit( TableScan scan ) {
            hashBasis.add( "TableScan#" + scan.getTable().getQualifiedName() );
            return scan;
        }


        @Override
        public RelNode visit( TableFunctionScan scan ) {
            hashBasis.add( "TableFunctionScan#" + scan.getTable().getQualifiedName() ); // TODO: This is most probably not sufficient
            return visitChildren( scan );
        }


        @Override
        public RelNode visit( LogicalValues values ) {
            return values;
        }


        @Override
        public RelNode visit( LogicalFilter filter ) {
            hashBasis.add( "LogicalFilter" );
            return visitChild( filter, 0, filter.getInput() );
        }


        @Override
        public RelNode visit( LogicalProject project ) {
            hashBasis.add( "LogicalProject#" + project.getProjects().size() );
            return visitChild( project, 0, project.getInput() );
        }


        @Override
        public RelNode visit( LogicalJoin join ) {
            hashBasis.add( "LogicalJoin#" + join.getLeft().getTable().getQualifiedName() + "#" + join.getRight().getTable().getQualifiedName() );
            return visitChildren( join );
        }


        @Override
        public RelNode visit( LogicalCorrelate correlate ) {
            hashBasis.add( "LogicalCorrelate" );
            return visitChildren( correlate );
        }


        @Override
        public RelNode visit( LogicalUnion union ) {
            hashBasis.add( "LogicalUnion" );
            return visitChildren( union );
        }


        @Override
        public RelNode visit( LogicalIntersect intersect ) {
            hashBasis.add( "LogicalIntersect" );
            return visitChildren( intersect );
        }


        @Override
        public RelNode visit( LogicalMinus minus ) {
            hashBasis.add( "LogicalMinus" );
            return visitChildren( minus );
        }


        @Override
        public RelNode visit( LogicalSort sort ) {
            hashBasis.add( "LogicalSort" );
            return visitChildren( sort );
        }


        @Override
        public RelNode visit( LogicalExchange exchange ) {
            hashBasis.add( "LogicalExchange#" + exchange.distribution.getType().shortName );
            return visitChildren( exchange );
        }


        @Override
        public RelNode visit( RelNode other ) {
            hashBasis.add( "other#" + other.getClass().getSimpleName() );
            return visitChildren( other );
        }
    }
}
