/*
3 * Licensed to the Apache Software Foundation (ASF) under one
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
package org.apache.asterix.lang.common.struct;

import java.util.Arrays;
import java.util.Optional;

public enum UnaryExprType {
    POSITIVE("+"),
    NEGATIVE("-"),
    EXISTS("exists"),
    NOT_EXISTS("not_exists");

    private final String symbol;

    UnaryExprType(String s) {
        symbol = s;
    }

    @Override
    public String toString() {
        return symbol;
    }

    public static Optional<UnaryExprType> fromSymbol(String symbol) {
        return Arrays.stream(UnaryExprType.values()).filter(o -> o.symbol.equals(symbol)).findFirst();
    }
}
