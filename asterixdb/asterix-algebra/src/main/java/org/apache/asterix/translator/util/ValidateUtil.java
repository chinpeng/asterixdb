/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.translator.util;

import java.util.ArrayList;
import java.util.List;

import org.apache.asterix.common.config.DatasetConfig.IndexType;
import org.apache.asterix.common.exceptions.AsterixException;
import org.apache.asterix.metadata.utils.KeyFieldTypeUtils;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.asterix.om.types.IAType;

/**
 * A util that can verify if a filter field, a list of partitioning expressions,
 * or a list of key fields are valid in a record type.
 */
public class ValidateUtil {

    /**
     * Validates the field that will be used as filter for the components of an LSM index.
     *
     * @param recType
     *            the record type
     * @param keyFieldNames
     *            a list of key fields that will be validated
     * @param indexType
     *            the type of the index that its key fields is being validated
     * @throws AsterixException
     *             (if the validation failed), IOException
     */
    public static void validateFilterField(ARecordType recType, List<String> filterField) throws AsterixException {
        IAType fieldType = recType.getSubFieldType(filterField);
        if (fieldType == null) {
            throw new AsterixException("A field with this name  \"" + filterField + "\" could not be found.");
        }
        switch (fieldType.getTypeTag()) {
            case INT8:
            case INT16:
            case INT32:
            case INT64:
            case FLOAT:
            case DOUBLE:
            case STRING:
            case BINARY:
            case DATE:
            case TIME:
            case DATETIME:
            case UUID:
            case YEARMONTHDURATION:
            case DAYTIMEDURATION:
                break;
            case UNION:
                throw new AsterixException("The filter field \"" + filterField + "\" cannot be nullable");
            default:
                throw new AsterixException("The field \"" + filterField + "\" which is of type "
                        + fieldType.getTypeTag() + " cannot be used as a filter for a dataset.");
        }
    }

    /**
     * Validates the partitioning expression that will be used to partition a dataset and returns expression type.
     *
     * @param partitioningExprs
     *            a list of partitioning expressions that will be validated
     * @return a list of partitioning expressions types
     * @throws AsterixException
     *             (if the validation failed), IOException
     */
    public static List<IAType> validatePartitioningExpressions(ARecordType recType, ARecordType metaRecType,
            List<List<String>> partitioningExprs, List<Integer> keySourceIndicators, boolean autogenerated)
                    throws AsterixException {
        List<IAType> partitioningExprTypes = new ArrayList<IAType>(partitioningExprs.size());
        if (autogenerated) {
            if (partitioningExprs.size() > 1) {
                throw new AsterixException("Cannot autogenerate a composite primary key");
            }
            List<String> fieldName = partitioningExprs.get(0);
            IAType fieldType = recType.getSubFieldType(fieldName);
            partitioningExprTypes.add(fieldType);

            ATypeTag pkTypeTag = fieldType.getTypeTag();
            if (pkTypeTag != ATypeTag.UUID) {
                throw new AsterixException("Cannot autogenerate a primary key for type " + pkTypeTag
                        + ". Autogenerated primary keys must be of type " + ATypeTag.UUID + ".");
            }
        } else {
            partitioningExprTypes = KeyFieldTypeUtils.getKeyTypes(recType, metaRecType, partitioningExprs,
                    keySourceIndicators);
            for (int fidx = 0; fidx < partitioningExprTypes.size(); ++fidx) {
                IAType fieldType = partitioningExprTypes.get(fidx);
                switch (fieldType.getTypeTag()) {
                    case INT8:
                    case INT16:
                    case INT32:
                    case INT64:
                    case FLOAT:
                    case DOUBLE:
                    case STRING:
                    case BINARY:
                    case DATE:
                    case TIME:
                    case UUID:
                    case DATETIME:
                    case YEARMONTHDURATION:
                    case DAYTIMEDURATION:
                        break;
                    case UNION:
                        throw new AsterixException(
                                "The partitioning key \"" + partitioningExprs.get(fidx) + "\" cannot be nullable");
                    default:
                        throw new AsterixException("The partitioning key \"" + partitioningExprs.get(fidx)
                                + "\" cannot be of type " + fieldType.getTypeTag() + ".");
                }
            }
        }
        return partitioningExprTypes;
    }

    /**
     * Validates the key fields that will be used as keys of an index.
     *
     * @param recType
     *            the record type
     * @param keyFieldNames
     *            a map of key fields that will be validated
     * @param keyFieldTypes
     *            a map of key types (if provided) that will be validated
     * @param indexType
     *            the type of the index that its key fields is being validated
     * @throws AsterixException
     *             (if the validation failed), IOException
     */
    public static void validateKeyFields(ARecordType recType, ARecordType metaRecType, List<List<String>> keyFieldNames,
            List<Integer> keySourceIndicators, List<IAType> keyFieldTypes, IndexType indexType)
                    throws AsterixException {
        List<IAType> fieldTypes = KeyFieldTypeUtils.getKeyTypes(recType, metaRecType, keyFieldNames,
                keySourceIndicators);
        int pos = 0;
        boolean openFieldCompositeIdx = false;
        for (IAType fieldType : fieldTypes) {
            List<String> fieldName = keyFieldNames.get(pos);
            if (fieldType == null) {
                fieldType = keyFieldTypes.get(pos);
                if (keyFieldTypes.get(pos) == BuiltinType.AMISSING) {
                    throw new AsterixException("A field with this name  \"" + fieldName + "\" could not be found.");
                }
            } else if (openFieldCompositeIdx) {
                throw new AsterixException("A closed field \"" + fieldName
                        + "\" could be only in a prefix part of the composite index, containing opened field.");
            }
            if (keyFieldTypes.get(pos) != BuiltinType.AMISSING
                    && fieldType.getTypeTag() != keyFieldTypes.get(pos).getTypeTag()) {
                throw new AsterixException(
                        "A field \"" + fieldName + "\" is already defined with the type \"" + fieldType + "\"");
            }
            switch (indexType) {
                case BTREE:
                    switch (fieldType.getTypeTag()) {
                        case INT8:
                        case INT16:
                        case INT32:
                        case INT64:
                        case FLOAT:
                        case DOUBLE:
                        case STRING:
                        case BINARY:
                        case DATE:
                        case TIME:
                        case DATETIME:
                        case UNION:
                        case UUID:
                        case YEARMONTHDURATION:
                        case DAYTIMEDURATION:
                            break;
                        default:
                            throw new AsterixException("The field \"" + fieldName + "\" which is of type "
                                    + fieldType.getTypeTag() + " cannot be indexed using the BTree index.");
                    }
                    break;
                case RTREE:
                    switch (fieldType.getTypeTag()) {
                        case POINT:
                        case LINE:
                        case RECTANGLE:
                        case CIRCLE:
                        case POLYGON:
                        case UNION:
                            break;
                        default:
                            throw new AsterixException("The field \"" + fieldName + "\" which is of type "
                                    + fieldType.getTypeTag() + " cannot be indexed using the RTree index.");
                    }
                    break;
                case LENGTH_PARTITIONED_NGRAM_INVIX:
                    switch (fieldType.getTypeTag()) {
                        case STRING:
                        case UNION:
                            break;
                        default:
                            throw new AsterixException(
                                    "The field \"" + fieldName + "\" which is of type " + fieldType.getTypeTag()
                                            + " cannot be indexed using the Length Partitioned N-Gram index.");
                    }
                    break;
                case LENGTH_PARTITIONED_WORD_INVIX:
                    switch (fieldType.getTypeTag()) {
                        case STRING:
                        case UNORDEREDLIST:
                        case ORDEREDLIST:
                        case UNION:
                            break;
                        default:
                            throw new AsterixException(
                                    "The field \"" + fieldName + "\" which is of type " + fieldType.getTypeTag()
                                            + " cannot be indexed using the Length Partitioned Keyword index.");
                    }
                    break;
                case SINGLE_PARTITION_NGRAM_INVIX:
                    switch (fieldType.getTypeTag()) {
                        case STRING:
                        case UNION:
                            break;
                        default:
                            throw new AsterixException("The field \"" + fieldName + "\" which is of type "
                                    + fieldType.getTypeTag() + " cannot be indexed using the N-Gram index.");
                    }
                    break;
                case SINGLE_PARTITION_WORD_INVIX:
                    switch (fieldType.getTypeTag()) {
                        case STRING:
                        case UNORDEREDLIST:
                        case ORDEREDLIST:
                        case UNION:
                            break;
                        default:
                            throw new AsterixException("The field \"" + fieldName + "\" which is of type "
                                    + fieldType.getTypeTag() + " cannot be indexed using the Keyword index.");
                    }
                    break;
                default:
                    throw new AsterixException("Invalid index type: " + indexType + ".");
            }
            pos++;
        }
    }

}
