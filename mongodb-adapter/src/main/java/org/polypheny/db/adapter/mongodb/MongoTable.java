/*
 * Copyright 2019-2020 The Polypheny Project
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
 *
 * This file incorporates code covered by the following terms:
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.polypheny.db.adapter.mongodb;


import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.function.Function1;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonNull;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.polypheny.db.adapter.AdapterManager;
import org.polypheny.db.adapter.DataContext;
import org.polypheny.db.adapter.java.AbstractQueryableTable;
import org.polypheny.db.adapter.mongodb.MongoEnumerator.ChangeMongoEnumerator;
import org.polypheny.db.adapter.mongodb.MongoEnumerator.IterWrapper;
import org.polypheny.db.catalog.Catalog;
import org.polypheny.db.catalog.entity.CatalogTable;
import org.polypheny.db.plan.Convention;
import org.polypheny.db.plan.RelOptCluster;
import org.polypheny.db.plan.RelOptTable;
import org.polypheny.db.prepare.Prepare.CatalogReader;
import org.polypheny.db.rel.RelNode;
import org.polypheny.db.rel.core.TableModify;
import org.polypheny.db.rel.core.TableModify.Operation;
import org.polypheny.db.rel.logical.LogicalTableModify;
import org.polypheny.db.rel.type.RelDataType;
import org.polypheny.db.rel.type.RelDataTypeFactory;
import org.polypheny.db.rel.type.RelProtoDataType;
import org.polypheny.db.rex.RexNode;
import org.polypheny.db.schema.ModifiableTable;
import org.polypheny.db.schema.SchemaPlus;
import org.polypheny.db.schema.TranslatableTable;
import org.polypheny.db.schema.impl.AbstractTableQueryable;
import org.polypheny.db.transaction.PolyXid;
import org.polypheny.db.type.PolyType;
import org.polypheny.db.util.Util;


/**
 * Table based on a MongoDB collection.
 */
public class MongoTable extends AbstractQueryableTable implements TranslatableTable, ModifiableTable {

    @Getter
    private final String collectionName;
    @Getter
    private final RelProtoDataType protoRowType;
    @Getter
    private final MongoSchema mongoSchema;
    @Getter
    private final MongoCollection<Document> collection;
    @Getter
    private final CatalogTable catalogTable;
    @Getter
    private final TransactionProvider transactionProvider;
    @Getter
    private final int storeId;


    /**
     * Creates a MongoTable.
     */
    MongoTable( CatalogTable catalogTable, MongoSchema schema, RelProtoDataType proto, TransactionProvider transactionProvider, int storeId ) {
        super( Object[].class );
        this.collectionName = MongoStore.getPhysicalTableName( catalogTable.id );
        this.transactionProvider = transactionProvider;
        this.catalogTable = catalogTable;
        this.protoRowType = proto;
        this.mongoSchema = schema;
        this.collection = schema.database.getCollection( collectionName );
        this.storeId = storeId;
    }


    public String toString() {
        return "MongoTable {" + collectionName + "}";
    }


    @Override
    public RelDataType getRowType( RelDataTypeFactory typeFactory ) {
        return protoRowType.apply( typeFactory );
    }


    @Override
    public <T> Queryable<T> asQueryable( DataContext dataContext, SchemaPlus schema, String tableName ) {
        return new MongoQueryable<>( dataContext, schema, this, tableName );
    }


    @Override
    public RelNode toRel( RelOptTable.ToRelContext context, RelOptTable relOptTable ) {
        final RelOptCluster cluster = context.getCluster();
        return new MongoTableScan( cluster, cluster.traitSetOf( MongoRel.CONVENTION ), relOptTable, this, null );
    }


    /**
     * Executes a "find" operation on the underlying collection.
     *
     * For example,
     * <code>zipsTable.find("{state: 'OR'}", "{city: 1, zipcode: 1}")</code>
     *
     * @param mongoDb MongoDB connection
     * @param filterJson Filter JSON string, or null
     * @param projectJson Project JSON string, or null
     * @param fields List of fields to project; or null to return map
     * @return Enumerator of results
     */
    private Enumerable<Object> find( MongoDatabase mongoDb, MongoTable table, String filterJson, String projectJson, List<Entry<String, Class>> fields, List<Entry<String, Class>> arrayFields ) {
        final MongoCollection collection = mongoDb.getCollection( collectionName );
        final Bson filter = filterJson == null ? null : BsonDocument.parse( filterJson );
        final Bson project = projectJson == null ? null : BsonDocument.parse( projectJson );
        final Function1<Document, Object> getter = MongoEnumerator.getter( fields, arrayFields );

        return new AbstractEnumerable<Object>() {
            @Override
            public Enumerator<Object> enumerator() {
                @SuppressWarnings("unchecked") final FindIterable<Document> cursor = collection.find( filter ).projection( project );
                return new MongoEnumerator( cursor.iterator(), getter, table.getMongoSchema().getBucket() );
            }
        };
    }


    /**
     * Executes an "aggregate" operation on the underlying collection.
     *
     * For example:
     * <code>zipsTable.aggregate(
     * "{$filter: {state: 'OR'}",
     * "{$group: {_id: '$city', c: {$sum: 1}, p: {$sum: '$pop'}}}")
     * </code>
     *
     * @param session
     * @param mongoDb MongoDB connection
     * @param fields List of fields to project; or null to return map
     * @param operations One or more JSON strings
     * @return Enumerator of results
     */
    private Enumerable<Object> aggregate( ClientSession session, final MongoDatabase mongoDb, MongoTable table, final List<Entry<String, Class>> fields, List<Entry<String, Class>> arrayFields, final List<String> operations ) {
        final List<Bson> list = new ArrayList<>();
        for ( String operation : operations ) {
            list.add( BsonDocument.parse( operation ) );
        }
        final Function1<Document, Object> getter = MongoEnumerator.getter( fields, arrayFields );
        return new AbstractEnumerable<Object>() {
            @Override
            public Enumerator<Object> enumerator() {
                final Iterator<Document> resultIterator;
                try {
                    if ( list.size() != 0 ) {
                        resultIterator = mongoDb.getCollection( collectionName ).aggregate( session, list ).iterator();
                    } else {
                        resultIterator = Collections.emptyIterator();
                    }
                } catch ( Exception e ) {
                    throw new RuntimeException( "While running MongoDB query " + Util.toString( operations, "[", ",\n", "]" ), e );
                }
                return new MongoEnumerator( resultIterator, getter, table.getMongoSchema().getBucket() );
            }
        };
    }


    /**
     * Helper method to strip non-numerics from a string.
     *
     * Currently used to determine mongod versioning numbers
     * from buildInfo.versionArray for use in aggregate method logic.
     */
    private static Integer parseIntString( String valueString ) {
        return Integer.parseInt( valueString.replaceAll( "[^0-9]", "" ) );
    }


    @Override
    public Collection getModifiableCollection() {
        throw new RuntimeException( "getModifiableCollection() is not implemented for MongoDB adapter!" );
    }


    @Override
    public TableModify toModificationRel(
            RelOptCluster cluster,
            RelOptTable table,
            CatalogReader catalogReader,
            RelNode child,
            Operation operation,
            List<String> updateColumnList,
            List<RexNode> sourceExpressionList,
            boolean flattened ) {
        mongoSchema.getConvention().register( cluster.getPlanner() );
        return new LogicalTableModify(
                cluster,
                cluster.traitSetOf( Convention.NONE ),
                table,
                catalogReader,
                child,
                operation,
                updateColumnList,
                sourceExpressionList,
                flattened );
    }


    /**
     * Implementation of {@link org.apache.calcite.linq4j.Queryable} based on a {@link org.polypheny.db.adapter.mongodb.MongoTable}.
     *
     * @param <T> element type
     */
    public static class MongoQueryable<T> extends AbstractTableQueryable<T> {

        MongoQueryable( DataContext dataContext, SchemaPlus schema, MongoTable table, String tableName ) {
            super( dataContext, schema, table, tableName );
        }


        @Override
        public Enumerator<T> enumerator() {
            //noinspection unchecked
            final Enumerable<T> enumerable = (Enumerable<T>) getTable().find( getMongoDb(), getTable(), null, null, null, null );
            return enumerable.enumerator();
        }


        private MongoDatabase getMongoDb() {
            return schema.unwrap( MongoSchema.class ).database;
        }


        private MongoTable getTable() {
            return (MongoTable) table;
        }


        /**
         * Called via code-generation.
         *
         * @see MongoMethod#MONGO_QUERYABLE_AGGREGATE
         */
        @SuppressWarnings("UnusedDeclaration")
        public Enumerable<Object> aggregate( List<Map.Entry<String, Class>> fields, List<Map.Entry<String, Class>> arrayClass, List<String> operations ) {
            return getTable().aggregate( getTable().getTransactionProvider().getSession( dataContext.getStatement().getTransaction().getXid() ), getMongoDb(), getTable(), fields, arrayClass, operations );
        }


        @SuppressWarnings("UnusedDeclaration")
        public Enumerable<Object> preparedAggregate( List<Map.Entry<String, Class>> fields, List<Map.Entry<String, Class>> arrayClasses, List<String> operations, List<Map.Entry<String, Map.Entry<String, Object>>> statics, List<Map.Entry<String, Map.Entry<String, Long>>> dynamics ) {
            long dyn = 0;
            List<String> filters = new ArrayList<>();

            for ( Entry<String, Entry<String, Long>> entry : dynamics ) {
                String filter;
                String val = MongoTypeUtil.getAsString( dataContext.getParameterValues().get( 0 ).get( dyn ) );
                if ( entry.getKey() == null ) {
                    filter = "\"" + entry.getValue().getKey() + "\":" + val;
                } else {
                    filter = entry.getValue().getKey() + ":{\"" + entry.getKey() + "\":" + val + "}";
                }
                filters.add( filter );
                dyn++;
            }

            statics.forEach( entry -> {
                String val = MongoTypeUtil.getAsString( entry.getValue().getValue() );
                if ( entry.getKey() == null ) {
                    filters.add( "\"" + entry.getValue().getKey() + "\":" + val );
                } else {
                    filters.add( entry.getValue().getKey() + ":{\"" + entry.getKey() + "\":" + val + "}" );
                }
            } );

            String mergedFilter = "{\n $match:\n{" + String.join( ",", filters ) + "}\n}";

            // mongodb allows to chain multiple $matches so we join our dynamic filters with
            // the predefined operations which results in $match:{}, $match:{[dynamic filter]}

            return aggregate( fields, arrayClasses, Stream.concat( Stream.of( mergedFilter ), operations.stream() ).collect( Collectors.toList() ) );
        }


        /**
         * Called via code-generation.
         *
         * @param filterJson Filter document
         * @param projectJson Projection document
         * @param fields List of expected fields (and their types)
         * @return result of mongo query
         * @see MongoMethod#MONGO_QUERYABLE_FIND
         */
        @SuppressWarnings("UnusedDeclaration")
        public Enumerable<Object> find( String filterJson, String projectJson, List<Map.Entry<String, Class>> fields, List<Map.Entry<String, Class>> arrayClasses ) {
            return getTable().find( getMongoDb(), getTable(), filterJson, projectJson, fields, arrayClasses );
        }


        @SuppressWarnings("UnusedDeclaration")
        public Enumerable<Object> preparedExecute( List<String> fieldNames, List<Integer> nullFields, Map<String, String> logicalPhysicalMapping, Map<Integer, PolyType> dynamicFields, Map<Integer, Object> staticValues, Map<Integer, List<Object>> arrayValues, Map<Integer, PolyType> types ) {
            MongoTable mongoTable = (MongoTable) table;
            PolyXid xid = dataContext.getStatement().getTransaction().getXid();
            dataContext.getStatement().getTransaction().registerInvolvedAdapter( AdapterManager.getInstance().getStore( mongoTable.getStoreId() ) );
            List<Document> docs = new ArrayList<>();

            for ( Map<Long, Object> values : this.dataContext.getParameterValues() ) {
                Document doc = new Document();

                // we traverse the columns and check if we have a static or a dynamic one
                int pos = 0;
                int dyn = 0;
                for ( String name : fieldNames ) {

                    if ( staticValues.containsKey( pos ) ) {
                        Object value = staticValues.get( pos );
                        if ( types.get( pos ) == PolyType.DECIMAL ) {
                            assert value instanceof String;
                            value = new Decimal128( new BigDecimal( (String) value ) );
                        }
                        doc.append( logicalPhysicalMapping.get( name ), value );
                    } else if ( dynamicFields.containsKey( pos ) ) {
                        doc.append( logicalPhysicalMapping.get( name ), MongoTypeUtil.getAsBson( values.get( (long) dyn ), dynamicFields.get( pos ), mongoTable.getMongoSchema().getBucket() ) );

                        dyn++;
                    } else if ( arrayValues.containsKey( pos ) ) {
                        PolyType type = types.get( pos );
                        BsonValue array = new BsonArray( arrayValues.get( pos ).stream().map( obj -> MongoTypeUtil.getAsBson( obj, type, mongoTable.getMongoSchema().getBucket() ) ).collect( Collectors.toList() ) );
                        doc.append( logicalPhysicalMapping.get( name ), array );
                    }

                    pos++;
                }
                docs.add( doc );
            }
            if ( this.dataContext.getParameterValues().size() == 0 ) {
                // prepared static entry
                Document doc = new Document();
                for ( Entry<Integer, Object> entry : staticValues.entrySet() ) {
                    Object value = entry.getValue();
                    if ( types.get( entry.getKey() ) == PolyType.DECIMAL ) {
                        assert value instanceof String;
                        value = new Decimal128( new BigDecimal( (String) value ) );
                    }
                    doc.append( logicalPhysicalMapping.get( fieldNames.get( entry.getKey() ) ), value );
                }
                for ( Integer key : nullFields ) {
                    doc.append( logicalPhysicalMapping.get( fieldNames.get( key ) ), new BsonNull() );
                }
                for ( Entry<Integer, List<Object>> entry : arrayValues.entrySet() ) {
                    PolyType type = types.get( entry.getKey() );
                    BsonValue array = new BsonArray( entry.getValue().stream().map( obj -> MongoTypeUtil.getAsBson( obj, type, mongoTable.getMongoSchema().getBucket() ) ).collect( Collectors.toList() ) );
                    doc.append( logicalPhysicalMapping.get( fieldNames.get( entry.getKey() ) ), array );
                }
                docs.add( doc );

            }

            if ( docs.size() > 0 ) {
                ClientSession session = mongoTable.getTransactionProvider().startTransaction( xid );
                mongoTable.getCollection().insertMany( session, docs );
            }
            return new AbstractEnumerable<Object>() {
                @Override
                public Enumerator<Object> enumerator() {
                    return new ChangeMongoEnumerator( Collections.singletonList( docs.size() ).iterator() );
                }
            };
        }


        /**
         * This methods handles prepared DMLs in Mongodb for now TODO DL: reevaluate
         *
         * @param context
         * @return
         */
        @SuppressWarnings("UnusedDeclaration")
        public Enumerable<Object> preparedWrapper( DataContext context ) {
            MongoTable mongoTable = (MongoTable) table;
            PolyXid xid = context.getStatement().getTransaction().getXid();
            List<Document> docs = new ArrayList<>();
            Map<Long, String> names = new HashMap<>();

            for ( Map<Long, Object> values : context.getParameterValues() ) {
                Document doc = new Document();

                for ( Map.Entry<Long, Object> value : values.entrySet() ) {
                    if ( !names.containsKey( value.getKey() ) ) {
                        names.put( value.getKey(), Catalog.getInstance().getColumn( value.getKey() ).name );
                    }
                    doc.append( names.get( value.getKey() ), value.getValue() );
                }
                docs.add( doc );
            }
            if ( docs.size() > 0 ) {
                mongoTable.transactionProvider.startTransaction( xid );

                context.getStatement().getTransaction().registerInvolvedAdapter( AdapterManager.getInstance().getStore( mongoTable.getStoreId() ) );

                mongoTable.getCollection().insertMany( mongoTable.transactionProvider.getSession( xid ), docs );
            }
            return new AbstractEnumerable<Object>() {
                @Override
                public Enumerator<Object> enumerator() {
                    return new IterWrapper( Collections.singletonList( (Object) docs.size() ).iterator() );
                }
            };
        }


        /**
         * This methods handles direct DMLs(which already have the values) in Mongodb for now TODO DL: reevaluate
         *
         * @return
         */
        @SuppressWarnings("UnusedDeclaration")
        public Enumerable<Object> handleDirectDML( Operation operation, String filter, List<String> operations ) {
            MongoTable mongoTable = (MongoTable) table;
            PolyXid xid = dataContext.getStatement().getTransaction().getXid();
            dataContext.getStatement().getTransaction().registerInvolvedAdapter( AdapterManager.getInstance().getStore( mongoTable.getStoreId() ) );
            ClientSession session = mongoTable.getTransactionProvider().startTransaction( xid );

            long changes = 0;

            switch ( operation ) {

                case INSERT:
                    List<Document> docs = operations.stream().map( Document::parse ).collect( Collectors.toList() );
                    mongoTable.getCollection().insertMany( session, docs );
                    changes = docs.size();
                    break;
                case UPDATE:
                    assert operations.size() == 1;
                    changes = mongoTable.getCollection().updateMany( session, BsonDocument.parse( filter ), BsonDocument.parse( operations.get( 0 ) ) ).getModifiedCount();
                    break;
                case DELETE:
                    assert filter != null;
                    changes = mongoTable.getCollection().deleteMany( session, BsonDocument.parse( filter ) ).getDeletedCount();
                    break;
                case MERGE:
                    throw new RuntimeException( "MERGE IS NOT SUPPORTED" );
            }

            long finalChanges = changes;
            return new AbstractEnumerable<Object>() {
                @Override
                public Enumerator<Object> enumerator() {
                    return new IterWrapper( Collections.singletonList( (Object) finalChanges ).iterator() );
                }
            };
        }

    }

}
