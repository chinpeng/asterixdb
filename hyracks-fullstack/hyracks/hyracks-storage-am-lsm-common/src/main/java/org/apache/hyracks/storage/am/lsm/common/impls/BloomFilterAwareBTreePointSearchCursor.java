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

package org.apache.hyracks.storage.am.lsm.common.impls;

import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.storage.am.bloomfilter.impls.BloomFilter;
import org.apache.hyracks.storage.am.btree.api.IBTreeLeafFrame;
import org.apache.hyracks.storage.am.btree.impls.BTreeRangeSearchCursor;

public class BloomFilterAwareBTreePointSearchCursor extends BTreeRangeSearchCursor {
    private BloomFilter bloomFilter;
    private long[] hashes = new long[2];

    public BloomFilterAwareBTreePointSearchCursor(IBTreeLeafFrame frame, boolean exclusiveLatchNodes,
            BloomFilter bloomFilter) {
        super(frame, exclusiveLatchNodes);
        this.bloomFilter = bloomFilter;
    }

    @Override
    public boolean hasNext() throws HyracksDataException {
        if (bloomFilter.contains(lowKey, hashes)) {
            return super.hasNext();
        }
        return false;
    }

    @Override
    public boolean isBloomFilterAware() {
        return true;
    }

    public void resetBloomFilter(BloomFilter bloomFilter) {
        this.bloomFilter = bloomFilter;
    }
}
