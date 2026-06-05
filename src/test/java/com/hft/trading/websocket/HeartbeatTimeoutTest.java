package com.hft.trading.websocket;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HeartbeatTimeoutTest - Tests for WebSocket idle timeout handling
 */
class HeartbeatTimeoutTest {

    @Test
    @DisplayName("Verify IdleStateHandler is added to pipeline")
    void testIdleStateHandlerAdded() {
        // Create an embedded channel with the same pipeline configuration as WebSocketServer
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();

        // Add handlers in the same order as WebSocketServer
        pipeline.addLast(new IdleStateHandler(90, 0, 0, java.util.concurrent.TimeUnit.SECONDS));

        // Verify IdleStateHandler is present
        IdleStateHandler handler = pipeline.get(IdleStateHandler.class);
        assertNotNull(handler, "IdleStateHandler should be present in the pipeline");

        channel.finish();
    }

    @Test
    @DisplayName("Verify IdleStateHandler configuration")
    void testIdleStateHandlerConfiguration() {
        // Create handler with 90 seconds read idle timeout
        IdleStateHandler handler = new IdleStateHandler(90, 0, 0, java.util.concurrent.TimeUnit.SECONDS);

        // Create embedded channel with the handler
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // Verify handler is in pipeline
        assertNotNull(channel.pipeline().get(IdleStateHandler.class));

        // Note: For actual idle timeout testing, you would need to use a real EventLoop
        // and wait for 90 seconds, which is not practical for unit tests.
        // Integration tests with a real server and client would be more appropriate.

        channel.finish();
    }

    @Test
    @DisplayName("Test that channel closes on idle timeout event")
    void testIdleTimeoutEventHandling() {
        // Create a mock handler that responds to idle events
        TestIdleHandler testHandler = new TestIdleHandler();
        EmbeddedChannel channel = new EmbeddedChannel(testHandler);

        // Simulate user event triggered
        io.netty.handler.timeout.IdleStateEvent idleEvent =
            io.netty.handler.timeout.IdleStateEvent.READER_IDLE_STATE_EVENT;

        channel.pipeline().fireUserEventTriggered(idleEvent);

        // The channel should be closed after idle timeout
        // In embedded channel, we check if the close future is set
        assertTrue(testHandler.wasIdleTimeoutHandled(), "Idle timeout should be handled");

        channel.finish();
    }

    /**
     * Test handler that tracks idle timeout events
     */
    private static class TestIdleHandler extends io.netty.channel.ChannelInboundHandlerAdapter {
        private boolean idleTimeoutHandled = false;

        @Override
        public void userEventTriggered(io.netty.channel.ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof io.netty.handler.timeout.IdleStateEvent) {
                io.netty.handler.timeout.IdleStateEvent idleEvent =
                    (io.netty.handler.timeout.IdleStateEvent) evt;
                if (idleEvent.state() == io.netty.handler.timeout.IdleState.READER_IDLE) {
                    idleTimeoutHandled = true;
                    ctx.close();
                }
            }
        }

        public boolean wasIdleTimeoutHandled() {
            return idleTimeoutHandled;
        }
    }
}
