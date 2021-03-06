/*
    Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.

    Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file
    except in compliance with the License. A copy of the License is located at

        http://aws.amazon.com/apache2.0/

    or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
    the specific language governing permissions and limitations under the License.
 */

package com.amazon.ask.attributes.persistence.impl;

import com.amazon.ask.attributes.persistence.PersistenceAdapter;
import com.amazon.ask.exception.PersistenceException;
import com.amazon.ask.model.RequestEnvelope;
import com.amazon.ask.util.ValidationUtils;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.ItemUtils;
import com.amazonaws.services.dynamodbv2.model.AmazonDynamoDBException;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.util.TableUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Persistence adapter for storing skill persistence attributes in Amazon DynamoDB.
 */
public final class DynamoDbPersistenceAdapter implements PersistenceAdapter {

    /**
     * Amazon DynamoDb client.
     */
    private final AmazonDynamoDB dynamoDb;

    /**
     * Table name to be used/created.
     */
    private final String tableName;

    /**
     * A simple primary key, composed of one attribute known as the partition key. DynamoDB uses the partition key's
     * value as input to an internal hash function. The output from the hash function determines the partition
     * (physical storage internal to DynamoDB) in which the item will be stored.
     */
    private final String partitionKeyName;

    /**
     * Referred to as a composite primary key, this type of key is composed of two attributes.
     * The first attribute is the partition key, and the second attribute is the sort key.
     */
    private final String attributesKeyName;

    /**
     * Partition key generator.
     */
    private final Function<RequestEnvelope, String> partitionKeyGenerator;

    /**
     * When set to true, creates table if table doesn't exist.
     */
    private boolean autoCreateTable;

    /**
     * Default partition key name.
     */
    private static final String DEFAULT_PARTITION_KEY_NAME = "id";

    /**
     * Default attributes key name.
     */
    private static final String DEFAULT_ATTRIBUTES_KEY_NAME = "attributes";

    /**
     * Default value for auto create table.
     */
    private static final boolean DEFAULT_AUTO_CREATE_TABLE = false;

    /**
     * Default partition key generator.
     */
    private static final Function<RequestEnvelope, String> DEFAULT_PARTITION_KEY_GENERATOR = PartitionKeyGenerators.userId();

    /**
     * Read capacity unit represents one strongly consistent read per second, or two eventually consistent reads per second,
     * for items up to 4 KB in size. If you need to read an item that is larger than 4 KB, DynamoDB will need to consume additional
     * read capacity units.
     */
    private static final Long DEFAULT_READ_CAPACITY_UNITS = 5L;

    /**
     * Private constructor to build an instance of {@link DynamoDbPersistenceAdapter}.
     * @param builder instance of {@link Builder}.
     */
    private DynamoDbPersistenceAdapter(final Builder builder) {
        this.tableName = ValidationUtils.assertStringNotEmpty(builder.tableName, "table name");
        this.dynamoDb = builder.dynamoDb != null ? builder.dynamoDb : AmazonDynamoDBClientBuilder.standard().build();
        this.partitionKeyName = builder.partitionKeyName;
        this.attributesKeyName = builder.attributesKeyName;
        this.partitionKeyGenerator = builder.partitionKeyGenerator;
        this.autoCreateTable = builder.autoCreateTable;
        autoCreateTableIfNotExists();
    }

    /**
     * Static method to build an instance of Builder.
     * @return {@link Builder}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets attributes from DynamoDB table.
     * @param envelope instance of {@link RequestEnvelope}.
     * @throws PersistenceException if table doesn't exist or attributes retrieval fails.
     * @return {@link Map} of String, Object if attributes exist, or an empty {@link Optional} if not.
     */
    @Override
    public Optional<Map<String, Object>> getAttributes(final RequestEnvelope envelope) throws PersistenceException {
        String partitionKey = partitionKeyGenerator.apply(envelope);
        GetItemRequest request = new GetItemRequest()
                .withTableName(tableName)
                .withKey(Collections.singletonMap(partitionKeyName, new AttributeValue().withS(partitionKey)))
                .withConsistentRead(true);
        Map<String, AttributeValue> result = null;
        try {
            result = dynamoDb.getItem(request).getItem();
        } catch (ResourceNotFoundException e) {
            throw new PersistenceException(String.format("Table %s does not exist or is in the process of being created", tableName), e);
        } catch (AmazonDynamoDBException e) {
            throw new PersistenceException("Failed to retrieve attributes from DynamoDB", e);
        }
        if (result != null && result.containsKey(attributesKeyName)) {
            Map<String, Object> attributes = ItemUtils.toSimpleMapValue(result.get(attributesKeyName).getM());
            return Optional.of(attributes);
        }
        return Optional.empty();
    }

    /**
     * Saves attributes to a DynamoDB table.
     * @param envelope instance of {@link RequestEnvelope}.
     * @param attributes to be stored in the table.
     * @throws PersistenceException if table doesn't exist or save attributes operation fails.
     */
    @Override
    public void saveAttributes(final RequestEnvelope envelope, final Map<String, Object> attributes) throws PersistenceException {
        String partitionKey = partitionKeyGenerator.apply(envelope);
        PutItemRequest request = new PutItemRequest()
                .withTableName(tableName)
                .withItem(getItem(partitionKey, attributes));
        try {
            dynamoDb.putItem(request);
        } catch (ResourceNotFoundException e) {
            throw new PersistenceException(String.format("Table %s does not exist or is in the process of being created", tableName), e);
        } catch (AmazonDynamoDBException e) {
            throw new PersistenceException("Failed to save attributes to DynamoDB", e);
        }
    }

    /**
     * Deletes attributes from DynamoDB table.
     * @param envelope instance of {@link RequestEnvelope}.
     * @throws PersistenceException if table doesn't exist or save attributes operation fails.
     */
    @Override
    public void deleteAttributes(final RequestEnvelope envelope) throws PersistenceException {
        String partitionKey = partitionKeyGenerator.apply(envelope);
        DeleteItemRequest deleteItemRequest = new DeleteItemRequest()
                .withTableName(tableName)
                .withKey(getItem(partitionKey, getAttributes(envelope).get()));
        try {
            dynamoDb.deleteItem(deleteItemRequest);
        } catch (ResourceNotFoundException e) {
            throw new PersistenceException(String.format("Table %s does not exist", tableName), e);
        } catch (AmazonDynamoDBException e) {
            throw new PersistenceException("Failed to delete attributes from DynamoDB", e);
        }
    }

    /**
     * Get a single item with a given id.
     * @param id of the item.
     * @param attributes DynamoDB table's attributes.
     * @return {@link Map} of id as key with corresponding attributeValue as value.
     */
    private Map<String, AttributeValue> getItem(final String id, final Map<String, Object> attributes) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(partitionKeyName, new AttributeValue().withS(id));
        item.put(attributesKeyName, new AttributeValue().withM(ItemUtils.fromSimpleMap(attributes)));
        return item;
    }

    /**
     * Auto creates table if the table doesn't exist.
     */
    private void autoCreateTableIfNotExists() {
        if (autoCreateTable) {
            AttributeDefinition partitionKeyDefinition = new AttributeDefinition()
                    .withAttributeName(partitionKeyName)
                    .withAttributeType(ScalarAttributeType.S);
            KeySchemaElement partitionKeySchema = new KeySchemaElement()
                    .withAttributeName(partitionKeyName)
                    .withKeyType(KeyType.HASH);
            ProvisionedThroughput throughput = new ProvisionedThroughput()
                    .withReadCapacityUnits(DEFAULT_READ_CAPACITY_UNITS)
                    .withWriteCapacityUnits(DEFAULT_READ_CAPACITY_UNITS);
            try {
                TableUtils.createTableIfNotExists(dynamoDb, new CreateTableRequest()
                        .withTableName(tableName)
                        .withAttributeDefinitions(partitionKeyDefinition)
                        .withKeySchema(partitionKeySchema)
                        .withProvisionedThroughput(throughput));
            } catch (AmazonDynamoDBException e) {
                throw new PersistenceException("Create table request failed", e);
            }
        }
    }

    /**
     * Static builder class to build an instance of {@link DynamoDbPersistenceAdapter}.
     */
    public static final class Builder {

        /**
         * Amazon DynamoDb client.
         */
        private AmazonDynamoDB dynamoDb;

        /**
         * Table name to be used/created.
         */
        private String tableName;

        /**
         * A simple primary key, composed of one attribute known as the partition key. DynamoDB uses the partition key's
         * value as input to an internal hash function. The output from the hash function determines the partition
         * (physical storage internal to DynamoDB) in which the item will be stored.
         */
        private String partitionKeyName = DEFAULT_PARTITION_KEY_NAME;

        /**
         * Referred to as a composite primary key, this type of key is composed of two attributes.
         * The first attribute is the partition key, and the second attribute is the sort key.
         */
        private String attributesKeyName = DEFAULT_ATTRIBUTES_KEY_NAME;

        /**
         * Partition key generator.
         */
        private Function<RequestEnvelope, String> partitionKeyGenerator = DEFAULT_PARTITION_KEY_GENERATOR;

        /**
         * When set to true, creates table if table doesn't exist.
         */
        private boolean autoCreateTable = DEFAULT_AUTO_CREATE_TABLE;

        /**
         * Prevent instantiation.
         */
        private Builder() { }

        /**
         * Optional DynamoDB client instance to use. If not provided, a default DynamoDB client instance
         * will be constructed and used.
         * @param dynamoDb client instance
         * @return builder
         */
        public Builder withDynamoDbClient(final AmazonDynamoDB dynamoDb) {
            this.dynamoDb = dynamoDb;
            return this;
        }

        /**
         * Name of the DynamoDB table to use for storing Skill persistence attributes.
         * @param tableName name of the DynamoDB table to use
         * @return builder
         */
        public Builder withTableName(final String tableName) {
            this.tableName = tableName;
            return this;
        }

        /**
         * Optional name of the partition key used to key attributes. By default {@value #DEFAULT_PARTITION_KEY_NAME}
         * is used.
         * @param partitionKeyName name of the partition key
         * @return builder
         */
        public Builder withPartitionKeyName(final String partitionKeyName) {
            this.partitionKeyName = partitionKeyName;
            return this;
        }

        /**
         * Optional name of the attributes key. By default {@value #DEFAULT_ATTRIBUTES_KEY_NAME} is used.
         * @param attributesKeyName name of the attribute key
         * @return builder
         */
        public Builder withAttributesKeyName(final String attributesKeyName) {
            this.attributesKeyName = attributesKeyName;
            return this;
        }

        /**
         * Optional partition key generator function used to derive partition key value from one or more
         * attributes of a {@link RequestEnvelope}. By default, {@link PartitionKeyGenerators#userId()} is used.
         * @param partitionKeyGenerator partition key generator function
         * @return builder
         */
        public Builder withPartitionKeyGenerator(final Function<RequestEnvelope, String> partitionKeyGenerator) {
            this.partitionKeyGenerator = partitionKeyGenerator;
            return this;
        }

        /**
         * Optional flag specifying whether the adapter should automatically create a table with the configured name
         * if it does not already exist. If not specified, this behavior defaults to true.
         * @param autoCreateTable true if the table should be automatically created if it does not already exist
         * @return builder
         */
        public Builder withAutoCreateTable(final boolean autoCreateTable) {
            this.autoCreateTable = autoCreateTable;
            return this;
        }

        /**
         * Builder method to build an instance of DynamoDbPersistenceAdapter.
         * @return {@link DynamoDbPersistenceAdapter}.
         */
        public DynamoDbPersistenceAdapter build() {
            return new DynamoDbPersistenceAdapter(this);
        }
    }

}
