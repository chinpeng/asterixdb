/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.asterix.runtime.pointables.printer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.EnumDeserializer;
import edu.uci.ics.asterix.runtime.pointables.AListPointable;
import edu.uci.ics.asterix.runtime.pointables.base.IVisitablePointable;
import edu.uci.ics.hyracks.algebricks.common.utils.Pair;

/**
 * This class is to do the runtime type cast for a list. It is ONLY visible to
 * ACastVisitor.
 */
class AListPrinter {
    private static String LEFT_PAREN = "{";
    private static String RIGHT_PAREN = "}";
    private static String COMMA = ",";

    private final Pair<PrintStream, ATypeTag> itemVisitorArg = new Pair<PrintStream, ATypeTag>(null, null);

    public AListPrinter() {

    }

    public void printList(AListPointable listAccessor, PrintStream ps, APrintVisitor visitor) throws IOException,
            AsterixException {
        List<IVisitablePointable> itemTags = listAccessor.getItemTags();
        List<IVisitablePointable> items = listAccessor.getItems();
        itemVisitorArg.first = ps;
        
        //print the beginning part
        ps.print(LEFT_PAREN);
        
        // print record 0 to n-2
        for (int i = 0; i < items.size() - 1; i++) {
            IVisitablePointable itemTypeTag = itemTags.get(i);
            IVisitablePointable item = items.get(i);
            ATypeTag typeTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(itemTypeTag.getByteArray()[itemTypeTag
                    .getStartOffset()]);
            itemVisitorArg.second = typeTag;
            item.accept(visitor, itemVisitorArg);
            //print the comma
            ps.print(COMMA);
        }
        
        // print record n-1
        IVisitablePointable itemTypeTag = itemTags.get(itemTags.size()-1);
        IVisitablePointable item = items.get(items.size()-1);
        ATypeTag typeTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(itemTypeTag.getByteArray()[itemTypeTag
                .getStartOffset()]);
        itemVisitorArg.second = typeTag;
        item.accept(visitor, itemVisitorArg);

        //print the end part
        ps.print(RIGHT_PAREN);
    }
}