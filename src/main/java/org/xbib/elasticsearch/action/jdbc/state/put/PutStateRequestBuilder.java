/*
 * Copyright (C) 2014 Jörg Prante
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.xbib.elasticsearch.action.jdbc.state.put;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.master.AcknowledgedRequestBuilder;
import org.elasticsearch.client.ClusterAdminClient;
import org.xbib.elasticsearch.jdbc.state.State;

public class PutStateRequestBuilder extends AcknowledgedRequestBuilder<PutStateRequest, PutStateResponse, PutStateRequestBuilder, ClusterAdminClient> {

    public PutStateRequestBuilder(ClusterAdminClient client) {
        super(client, new PutStateRequest());
    }

    public PutStateRequestBuilder setName(String name) {
        request.setName(name);
        return this;
    }

    public PutStateRequestBuilder setState(State state) {
        request.setState(state);
        return this;
    }

    @Override
    protected void doExecute(ActionListener<PutStateResponse> listener) {
        client.execute(PutStateAction.INSTANCE, request, listener);
    }
}