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
package org.apache.asterix.external.input.record.reader.twitter;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.asterix.external.api.IRawRecord;
import org.apache.asterix.external.api.IRecordReader;
import org.apache.asterix.external.dataflow.AbstractFeedDataFlowController;
import org.apache.asterix.external.input.record.GenericRecord;
import org.apache.asterix.external.util.FeedLogManager;

import twitter4j.FilterQuery;
import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.StatusListener;
import twitter4j.TwitterStream;

public class TwitterPushRecordReader implements IRecordReader<Status> {
    private LinkedBlockingQueue<Status> inputQ;
    private TwitterStream twitterStream;
    private GenericRecord<Status> record;
    private boolean closed = false;

    public TwitterPushRecordReader(TwitterStream twitterStream, FilterQuery query) {
        record = new GenericRecord<Status>();
        inputQ = new LinkedBlockingQueue<Status>();
        this.twitterStream = twitterStream;//TwitterUtil.getTwitterStream(configuration);
        this.twitterStream.addListener(new TweetListener(inputQ));
        this.twitterStream.filter(query);
    }

    public TwitterPushRecordReader(TwitterStream twitterStream) {
        record = new GenericRecord<Status>();
        inputQ = new LinkedBlockingQueue<Status>();
        this.twitterStream = twitterStream;//
        this.twitterStream.addListener(new TweetListener(inputQ));
        twitterStream.sample();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            twitterStream.clearListeners();
            twitterStream.cleanUp();
            twitterStream = null;
            closed = true;
        }
    }

    @Override
    public boolean hasNext() throws Exception {
        return !closed;
    }

    @Override
    public IRawRecord<Status> next() throws IOException, InterruptedException {
        Status tweet = inputQ.poll();
        if (tweet == null) {
            return null;
        }
        record.set(tweet);
        return record;
    }

    @Override
    public boolean stop() {
        try {
            close();
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private class TweetListener implements StatusListener {

        private LinkedBlockingQueue<Status> inputQ;

        public TweetListener(LinkedBlockingQueue<Status> inputQ) {
            this.inputQ = inputQ;
        }

        @Override
        public void onStatus(Status tweet) {
            inputQ.add(tweet);
        }

        @Override
        public void onException(Exception arg0) {

        }

        @Override
        public void onDeletionNotice(StatusDeletionNotice arg0) {
        }

        @Override
        public void onScrubGeo(long arg0, long arg1) {
        }

        @Override
        public void onStallWarning(StallWarning arg0) {
        }

        @Override
        public void onTrackLimitationNotice(int arg0) {
        }
    }

    @Override
    public void setFeedLogManager(FeedLogManager feedLogManager) {
    }

    @Override
    public void setController(AbstractFeedDataFlowController controller) {
    }

    @Override
    public boolean handleException(Throwable th) {
        return false;
    }
}
