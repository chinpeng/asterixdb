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
package org.apache.asterix.runtime.evaluators.common;

import java.io.IOException;

import org.apache.asterix.builders.OrderedListBuilder;
import org.apache.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import org.apache.asterix.om.base.ABoolean;
import org.apache.asterix.om.functions.AsterixBuiltinFunctions;
import org.apache.asterix.om.types.AOrderedListType;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.asterix.om.types.EnumDeserializer;
import org.apache.asterix.om.types.hierachy.ATypeHierarchy;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluator;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.ISerializerDeserializer;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.data.std.api.IPointable;
import org.apache.hyracks.data.std.primitive.VoidPointable;
import org.apache.hyracks.data.std.util.ArrayBackedValueStorage;
import org.apache.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

public class EditDistanceCheckEvaluator extends EditDistanceEvaluator {

    protected final IScalarEvaluator edThreshEval;
    protected int edThresh;
    private final IPointable argPtrThreshold = new VoidPointable();
    protected final OrderedListBuilder listBuilder;
    protected ArrayBackedValueStorage listItemVal;
    @SuppressWarnings("unchecked")
    protected final ISerializerDeserializer<ABoolean> booleanSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ABOOLEAN);

    public EditDistanceCheckEvaluator(IScalarEvaluatorFactory[] args, IHyracksTaskContext context)
            throws AlgebricksException {
        super(args, context);
        edThreshEval = args[2].createScalarEvaluator(context);
        listBuilder = new OrderedListBuilder();
        listItemVal = new ArrayBackedValueStorage();
    }

    @Override
    public void evaluate(IFrameTupleReference tuple, IPointable result) throws AlgebricksException {
        resultStorage.reset();

        firstStringEval.evaluate(tuple, argPtr1);
        firstTypeTag = EnumDeserializer.ATYPETAGDESERIALIZER
                .deserialize(argPtr1.getByteArray()[argPtr1.getStartOffset()]);
        secondStringEval.evaluate(tuple, argPtr2);
        secondTypeTag = EnumDeserializer.ATYPETAGDESERIALIZER
                .deserialize(argPtr2.getByteArray()[argPtr2.getStartOffset()]);
        edThreshEval.evaluate(tuple, argPtrThreshold);

        if (!checkArgTypes(firstTypeTag, secondTypeTag)) {
            result.set(resultStorage);
            return;
        }
        try {
            edThresh = ATypeHierarchy.getIntegerValue(argPtrThreshold.getByteArray(), argPtrThreshold.getStartOffset());
            editDistance = computeResult(argPtr1, argPtr2, firstTypeTag);
            writeResult(editDistance);
        } catch (IOException e) {
            throw new AlgebricksException(e);
        }
        result.set(resultStorage);
    }

    @Override
    protected int computeResult(IPointable left, IPointable right, ATypeTag argType)
            throws AlgebricksException, HyracksDataException {
        byte[] leftBytes = left.getByteArray();
        int leftStartOffset = left.getStartOffset();
        byte[] rightBytes = right.getByteArray();
        int rightStartOffset = right.getStartOffset();
        switch (argType) {
            case STRING: {
                return ed.UTF8StringEditDistance(leftBytes, leftStartOffset + typeIndicatorSize, rightBytes,
                        rightStartOffset + typeIndicatorSize, edThresh);
            }

            case ORDEREDLIST: {
                firstOrdListIter.reset(leftBytes, leftStartOffset);
                secondOrdListIter.reset(rightBytes, rightStartOffset);
                return (int) ed.getSimilarity(firstOrdListIter, secondOrdListIter, edThresh);
            }

            default: {
                throw new AlgebricksException(AsterixBuiltinFunctions.EDIT_DISTANCE_CHECK.getName()
                        + ": expects input type as STRING or ORDEREDLIST but got " + argType + ".");
            }

        }
    }

    @Override
    protected void writeResult(int ed) throws IOException {
        listBuilder.reset(new AOrderedListType(BuiltinType.ANY, "list"));
        boolean matches = (ed < 0) ? false : true;
        listItemVal.reset();
        booleanSerde.serialize(matches ? ABoolean.TRUE : ABoolean.FALSE, listItemVal.getDataOutput());
        listBuilder.addItem(listItemVal);

        listItemVal.reset();
        aInt64.setValue((matches) ? ed : Integer.MAX_VALUE);
        int64Serde.serialize(aInt64, listItemVal.getDataOutput());
        listBuilder.addItem(listItemVal);
        listBuilder.write(out, true);
    }
}
