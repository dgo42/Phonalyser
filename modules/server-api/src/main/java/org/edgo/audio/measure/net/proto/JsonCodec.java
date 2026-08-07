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

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The only place in the bridge that owns a JSON mapper: text in, protocol
 * values out.  One instance is created by the server (and by the client
 * connection) and passed to whoever needs it - the mapper is thread-safe once
 * configured, so a session can share it across its reader and writer threads.
 *
 * <p>The single piece of configuration is the forward-compatibility rule of
 * spec 1: unknown JSON fields are ignored instead of failing the bind.  Null
 * inclusion is deliberately left at the Jackson default, because the protocol
 * uses an explicit null as a value - {@code lock: null} means "free" in the
 * device list (spec 4.3).
 */
public final class JsonCodec {

    /** What {@link #toMap} binds a payload object to: field name -> plain value
     *  (a number, a string, a boolean, or a nested list/map). */
    private static final TypeReference<Map<String, Object>> MAP_OF_FIELDS =
            new TypeReference<>() { };

    private final ObjectMapper mapper;

    public JsonCodec() {
        this.mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** Serialises a control message; the envelope is already a JSON object, so
     *  this cannot fail. */
    public String write(NetMessage message) {
        return message.getRoot().toString();
    }

    /** Serialises any other protocol value - a beacon, a payload record, a map. */
    public String write(Object value) throws JsonProcessingException {
        return mapper.writeValueAsString(value);
    }

    /** Parses one control message. Malformed JSON raises the Jackson parse
     *  exception; valid JSON that is not an object is a protocol error. */
    public NetMessage read(String json) throws JsonProcessingException {
        JsonNode node = mapper.readTree(json);
        if (!node.isObject()) {
            throw new IllegalArgumentException(
                    "net control message is not a JSON object: " + json);
        }
        return new NetMessage((ObjectNode) node);
    }

    /** Binds a whole document - a beacon datagram, a payload record. */
    public <T> T read(String json, Class<T> type) throws JsonProcessingException {
        return mapper.readValue(json, type);
    }

    /** Turns a payload value into the node a response or event carries. */
    public JsonNode toNode(Object value) {
        return mapper.valueToTree(value);
    }

    /** Binds a payload node (typically {@code data}) to a record. */
    public <T> T fromNode(JsonNode node, Class<T> type) throws JsonProcessingException {
        return mapper.treeToValue(node, type);
    }

    /**
     * A payload object as a plain map of its fields, for a consumer that has no
     * record to bind to - a backend extension whose payload belongs to that
     * backend and not to this module (net protocol 4.6).  A node that is not an
     * object answers an empty map: absent and shapeless are the same answer to a
     * caller reading fields out of it.
     */
    public Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(node, MAP_OF_FIELDS);
    }
}
