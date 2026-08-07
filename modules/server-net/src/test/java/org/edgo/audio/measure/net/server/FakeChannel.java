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

package org.edgo.audio.measure.net.server;

import java.util.ArrayList;
import java.util.List;

import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetMessage;

import lombok.Getter;

/**
 * A {@link SessionChannel} that keeps what was written instead of writing it,
 * so a session can be driven through its whole protocol without a socket.
 *
 * <p>The audio frames are kept in their own list: they travel on the same socket
 * as the control messages (spec 5) but they are a different assertion - a test
 * reads a frame sequence and its packet numbers, not a conversation.
 */
final class FakeChannel implements SessionChannel {

    @Getter
    private final List<NetMessage> sent = new ArrayList<>();
    @Getter
    private final List<BinaryFrame> frames = new ArrayList<>();
    @Getter
    private String closeReason;
    @Getter
    private int closeCount;

    /** How many times the transport still answers "behind", and how often it was
     *  asked at all - the audio lane's only backpressure, so a test asserts that
     *  it really was consulted. */
    @Getter
    private int backlogQuestions;
    private int backloggedAnswers;

    private boolean failNextSend;
    private boolean failFrames;

    @Override
    public void send(NetMessage message) {
        if (failNextSend) {
            failNextSend = false;
            throw new IllegalStateException("the writer blew up");
        }
        sent.add(message);
    }

    @Override
    public void send(BinaryFrame frame) {
        if (failFrames) {
            throw new IllegalStateException("the audio lane blew up");
        }
        frames.add(frame);
    }

    /** A socket that keeps up, unless {@link #backlogFor(int)} says otherwise. */
    @Override
    public boolean isSendBacklogged() {
        backlogQuestions++;
        if (backloggedAnswers > 0) {
            backloggedAnswers--;
            return true;
        }
        return false;
    }

    @Override
    public void close(String reason) {
        closeReason = reason;
        closeCount++;
    }

    /** Reports unwritten bytes for the next {@code answers} questions and then
     *  catches up - a client that fell behind and recovered. */
    void backlogFor(int answers) {
        backloggedAnswers = answers;
    }

    /** Makes the next send throw - a handler failing for a reason the protocol
     *  does not name, which spec 4.2 answers with {@code INTERNAL}. */
    void failOnce() {
        failNextSend = true;
    }

    /** Makes every audio frame throw from here on - a capture lane that fails
     *  after {@code capture.start}, which no response and no GAP can report. */
    void failFrames() {
        failFrames = true;
    }

    /** Every server-initiated event of one type written so far, in order. */
    List<NetMessage> events(MessageType type) {
        List<NetMessage> found = new ArrayList<>();
        for (NetMessage message : sent) {
            if (message.getType() == type) {
                found.add(message);
            }
        }
        return found;
    }

    /** The answer to the request with this id, or null when none was sent. */
    NetMessage responseTo(int id) {
        NetMessage found = null;
        for (NetMessage message : sent) {
            if (message.getType() == MessageType.RESP
                    && Integer.valueOf(id).equals(message.getId())) {
                found = message;
            }
        }
        return found;
    }

    /** Every server-initiated ping written so far, in order. */
    List<NetMessage> pings() {
        List<NetMessage> found = new ArrayList<>();
        for (NetMessage message : sent) {
            if (message.getType() == MessageType.PING) {
                found.add(message);
            }
        }
        return found;
    }
}
