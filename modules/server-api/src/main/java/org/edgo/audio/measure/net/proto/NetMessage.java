/*
 * Phonalyser - precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.net.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.Getter;

/**
 * One control-plane message - the envelope of spec 4.0 in its three shapes:
 * a request {@code {"t":...,"id":...,...}}, a response
 * {@code {"t":"resp","id":...,"ok":...}} and a server event
 * {@code {"t":"ev....",...}}.
 *
 * <p>The message keeps the JSON object it was built from or parsed into rather
 * than a fixed set of typed fields, and that is deliberate:
 *
 * <ul>
 *   <li><b>Presence is data.</b> {@code gen.config} is a partial update - only
 *       the fields actually present are applied - so "absent" must stay
 *       distinguishable from "zero". The {@code optXxx} accessors answer null
 *       for an absent field and never invent a default.</li>
 *   <li><b>Unknown fields survive.</b> Spec 1 orders both sides to ignore
 *       fields they do not know; keeping the parsed object means an unknown
 *       field is not merely ignored but still there for logging and relaying.</li>
 * </ul>
 *
 * <p>Payload structures that DO have a fixed shape (a device list, a generator
 * state) travel inside {@code data} and are bound to records through
 * {@link JsonCodec#fromNode}; this type only owns the envelope.
 */
public final class NetMessage {

    private static final String FIELD_TYPE = "t";
    private static final String FIELD_ID = "id";
    private static final String FIELD_OK = "ok";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_CODE = "code";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_BY = "by";
    private static final String FIELD_REASON = NetFields.REASON;

    /** The message exactly as it goes on - or came off - the wire. */
    @Getter
    private final ObjectNode root;

    /** Request: client to server, or a server-initiated {@code ping} whose
     *  {@code id} is negative so it cannot collide with a client id. */
    public NetMessage(MessageType type, int id) {
        this(JsonNodeFactory.instance.objectNode());
        root.put(FIELD_TYPE, type.getWire());
        root.put(FIELD_ID, id);
    }

    /** Server event: no {@code id}, never answered. */
    public NetMessage(MessageType type) {
        this(JsonNodeFactory.instance.objectNode());
        root.put(FIELD_TYPE, type.getWire());
    }

    /** Successful response with an empty payload - the answer to
     *  {@code ping}, {@code bye}, {@code capture.start} and friends. */
    public NetMessage(int id) {
        this(id, JsonNodeFactory.instance.objectNode());
    }

    /** Successful response carrying {@code data}. */
    public NetMessage(int id, JsonNode data) {
        this(JsonNodeFactory.instance.objectNode());
        root.put(FIELD_TYPE, MessageType.RESP.getWire());
        root.put(FIELD_ID, id);
        root.put(FIELD_OK, true);
        root.set(FIELD_DATA, data == null ? JsonNodeFactory.instance.objectNode() : data);
    }

    /** Failed response. */
    public NetMessage(int id, NetError error) {
        this(JsonNodeFactory.instance.objectNode());
        root.put(FIELD_TYPE, MessageType.RESP.getWire());
        root.put(FIELD_ID, id);
        root.put(FIELD_OK, false);
        ObjectNode node = root.putObject(FIELD_ERROR);
        node.put(FIELD_CODE, error.code());
        node.put(FIELD_MESSAGE, error.message());
        if (error.by() != null) {
            node.put(FIELD_BY, error.by());
        }
        if (error.reason() != null) {
            node.put(FIELD_REASON, error.reason());
        }
    }

    /** Wraps an already parsed message - {@link JsonCodec#read(String)}. */
    public NetMessage(ObjectNode root) {
        this.root = root;
    }

    /** The decoded discriminator; {@link MessageType#UNKNOWN} when this build
     *  does not know the type (answer {@code UNSUPPORTED}, spec 1). */
    public MessageType getType() {
        return MessageType.fromWire(getT());
    }

    /** The raw {@code t} text, unknown types included - for the error message
     *  a rejected message deserves. */
    public String getT() {
        return optString(FIELD_TYPE);
    }

    /** The correlation id, or null for an event (spec 4.0). */
    public Integer getId() {
        return optInt(FIELD_ID);
    }

    /** True only for a successful response. */
    public boolean isOk() {
        return root.path(FIELD_OK).asBoolean(false);
    }

    /** The {@code data} payload of a successful response; a missing node (not
     *  null) when absent, so {@code getData().path("rate")} always works. */
    public JsonNode getData() {
        return root.path(FIELD_DATA);
    }

    /** The {@code error} object of a failed response, or null when absent. */
    public NetError getError() {
        JsonNode node = root.path(FIELD_ERROR);
        if (!node.isObject()) {
            return null;
        }
        return new NetError(text(node, FIELD_CODE), text(node, FIELD_MESSAGE),
                text(node, FIELD_BY), text(node, FIELD_REASON));
    }

    /** True when the field is present and not JSON null. */
    public boolean has(String field) {
        return root.hasNonNull(field);
    }

    /** The raw field node; a missing node when absent. */
    public JsonNode getNode(String field) {
        return root.path(field);
    }

    /** Null when absent - an absent number is NOT zero (partial updates). */
    public Integer optInt(String field) {
        JsonNode node = root.path(field);
        return node.isNumber() ? node.asInt() : null;
    }

    /** Null when absent. */
    public Long optLong(String field) {
        JsonNode node = root.path(field);
        return node.isNumber() ? node.asLong() : null;
    }

    /** Null when absent. */
    public Double optDouble(String field) {
        JsonNode node = root.path(field);
        return node.isNumber() ? node.asDouble() : null;
    }

    /** Null when absent. */
    public Boolean optBoolean(String field) {
        JsonNode node = root.path(field);
        return node.isBoolean() ? node.asBoolean() : null;
    }

    /** Null when absent. */
    public String optString(String field) {
        JsonNode node = root.path(field);
        return node.isTextual() ? node.asText() : null;
    }

    /** Adds a field to a request or event; returns this for chaining. */
    public NetMessage put(String field, int value) {
        root.put(field, value);
        return this;
    }

    /** Adds a field to a request or event; returns this for chaining. */
    public NetMessage put(String field, long value) {
        root.put(field, value);
        return this;
    }

    /** Adds a field to a request or event; returns this for chaining. */
    public NetMessage put(String field, double value) {
        root.put(field, value);
        return this;
    }

    /** Adds a field to a request or event; returns this for chaining. */
    public NetMessage put(String field, boolean value) {
        root.put(field, value);
        return this;
    }

    /** Adds a field to a request or event; returns this for chaining. */
    public NetMessage put(String field, String value) {
        root.put(field, value);
        return this;
    }

    /** Adds a structured field (an object or array built by the codec). */
    public NetMessage put(String field, JsonNode value) {
        root.set(field, value);
        return this;
    }

    @Override
    public String toString() {
        return root.toString();
    }

    private String text(JsonNode owner, String field) {
        JsonNode node = owner.path(field);
        return node.isTextual() ? node.asText() : null;
    }
}
