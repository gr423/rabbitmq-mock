package com.github.fridujo.rabbitmq.mock;

import static com.github.fridujo.rabbitmq.mock.configuration.QueueDeclarator.dynamicQueue;
import static com.github.fridujo.rabbitmq.mock.configuration.QueueDeclarator.queue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ExtensionTest {

    @Test
    void alternate_exchange_is_used_when_routing_fails() throws IOException, TimeoutException {
        try (Connection conn = new MockConnectionFactory().newConnection()) {
            try (Channel channel = conn.createChannel()) {
                channel.exchangeDeclare("ex-1", BuiltinExchangeType.TOPIC, true, false, Collections.singletonMap("alternate-exchange", "ex-2"));
                channel.exchangeDeclare("ex-2", BuiltinExchangeType.FANOUT);
                channel.queueDeclare("fruits", true, false, false, Collections.emptyMap());
                channel.queueDeclare("unrouted", true, false, false, Collections.emptyMap());

                assertThat(channel.queueBind("fruits", "ex-1", "fruit.*")).isNotNull();
                assertThat(channel.queueBind("unrouted", "ex-2", "")).isNotNull();

                channel.basicPublish("ex-1", "vegetable.carrot", null, "carrot".getBytes());
                channel.basicPublish("ex-1", "fruit.orange", null, "orange".getBytes());

                GetResponse response = channel.basicGet("fruits", true);
                assertThat(response).isNotNull();
                assertThat(new String(response.getBody())).isEqualTo("orange");
                assertThat(channel.basicGet("fruits", true)).isNull();

                response = channel.basicGet("unrouted", true);
                assertThat(response).isNotNull();
                assertThat(new String(response.getBody())).isEqualTo("carrot");
                assertThat(channel.basicGet("unrouted", true)).isNull();
            }
        }
    }
    
    @Test
    void check_xdeath_header_count_increments_for_the_same_q_and_reason() throws IOException, TimeoutException, InterruptedException {
    	try (Connection conn = new MockConnectionFactory().newConnection()) {
    		try (Channel channel = conn.createChannel()) {
    			
    			Semaphore se = new Semaphore(0);
    			
    			  List<Map<String,Object>> xdeathHeaders = new ArrayList<>();
    			 
    			channel.exchangeDeclare("rejected-ex", BuiltinExchangeType.FANOUT);

    			Map<String, Object> args = new HashMap<>();
    			args.put("x-message-ttl", 0);//immediate
    			args.put("x-dead-letter-exchange", "");
    			channel.queueDeclare("rejected", true, false, false, args);
    			channel.queueBindNoWait("rejected", "rejected-ex", "unused", null);
    			channel.queueDeclare("fruits", true, false, false, Collections.singletonMap("x-dead-letter-exchange", "rejected-ex"));

    			channel.basicConsume("fruits", new DefaultConsumer(channel) {
    				@Override
    				public void handleDelivery(String consumerTag,
    						Envelope envelope,
    						AMQP.BasicProperties properties,
    						byte[] body) throws IOException {

    					if(properties.getHeaders()!=null)
    					{
                           List<Map<String,Object>> headers = (List<Map<String, Object>>) properties.getHeaders().get("x-death");

    						if(headers!=null)
    						{
    							Map<String, Object> xDeathInfo = headers.stream().filter(xDeath -> "fruits".equalsIgnoreCase(xDeath.get("queue").toString()) && "rejected".equalsIgnoreCase(xDeath.get("reason").toString())).findAny().orElse(null);

    							if(xDeathInfo!=null)
    							{
    								Long count  = (Long) xDeathInfo.get("count");
    								if(count == 4)
    								{
    									channel.basicAck(envelope.getDeliveryTag(), false);
                                        xdeathHeaders.addAll(headers);
    									se.release();
                                    }
    							}

    						}
    					}

    					channel.basicNack(envelope.getDeliveryTag(), false, false);

    				}
    			});

    			channel.basicPublish("", "fruits", null, "banana".getBytes());
    			
    			se.acquire();
    			
    			assertEquals(xdeathHeaders.get(0).get("reason").toString(), "expired");
				assertEquals(xdeathHeaders.get(0).get("queue").toString(), "rejected");

                assertEquals(xdeathHeaders.get(1).get("reason").toString(), "rejected");
				assertEquals(xdeathHeaders.get(1).get("queue").toString(), "fruits");

    		}
    	}
    }

    @Test
    void dead_letter_exchange_is_used_when_a_message_is_rejected_without_requeue() throws IOException, TimeoutException {
        try (Connection conn = new MockConnectionFactory().newConnection()) {
            try (Channel channel = conn.createChannel()) {
                channel.exchangeDeclare("rejected-ex", BuiltinExchangeType.FANOUT);
                channel.queueDeclare("rejected", true, false, false, null);
                channel.queueBindNoWait("rejected", "rejected-ex", "unused", null);
                channel.queueDeclare("fruits", true, false, false, Collections.singletonMap("x-dead-letter-exchange", "rejected-ex"));

                channel.basicPublish("", "fruits", null, "banana".getBytes());
                GetResponse getResponse = channel.basicGet("fruits", false);
                channel.basicNack(getResponse.getEnvelope().getDeliveryTag(), false, true);
                assertThat(channel.messageCount("rejected")).isEqualTo(0);
                assertThat(channel.messageCount("fruits")).isEqualTo(1);

                getResponse = channel.basicGet("fruits", false);
                channel.basicReject(getResponse.getEnvelope().getDeliveryTag(), false);
                assertThat(channel.messageCount("rejected")).isEqualTo(1);
                GetResponse basicGet = channel.basicGet("rejected", true);
                
                List<Map<String,Object>> xDeathHeaders = (List<Map<String, Object>>) basicGet.getProps().getHeaders().get("x-death");
                assertEquals(xDeathHeaders.get(0).get("reason").toString(), "rejected");
                assertEquals(xDeathHeaders.get(0).get("queue").toString(), "fruits");
                assertThat(channel.messageCount("fruits")).isEqualTo(0);
            }
        }
    }

    @Test
    void dead_letter_routing_key_is_used_when_a_message_is_rejected_without_requeue() throws IOException, TimeoutException {
        try (Connection conn = new MockConnectionFactory().newConnection()) {
            try (Channel channel = conn.createChannel()) {
                channel.queueDeclare("rejected", true, false, false, null);
                channel.queueBindNoWait("rejected", "", "rejected", null);
                queue("fruits")
                    .withDeadLetterExchange("")
                    .withDeadLetterRoutingKey("rejected")
                    .declare(channel);

                channel.basicPublish("", "fruits", null, "banana".getBytes());
                GetResponse getResponse = channel.basicGet("fruits", false);
                channel.basicNack(getResponse.getEnvelope().getDeliveryTag(), false, true);
                assertThat(channel.messageCount("rejected")).isEqualTo(0);
                assertThat(channel.messageCount("fruits")).isEqualTo(1);

                getResponse = channel.basicGet("fruits", false);
                channel.basicReject(getResponse.getEnvelope().getDeliveryTag(), false);
                assertThat(channel.messageCount("rejected")).isEqualTo(1);
                assertThat(channel.messageCount("fruits")).isEqualTo(0);
            }
        }
    }

    @Nested
    class TimeToLiveExtension {

        @Test
        void message_ttl_for_queue_keeps_messages_while_expiration_not_reached() throws IOException, TimeoutException {
            try (Connection conn = new MockConnectionFactory().newConnection()) {
                try (Channel channel = conn.createChannel()) {
                    queue("fruits").withMessageTtl(700).declare(channel);
                    channel.basicPublish("", "fruits", null, "banana".getBytes());
                    GetResponse getResponse = channel.basicGet("fruits", true);
                    assertThat(getResponse).isNotNull();
                }
            }
        }

        @Test
        void message_ttl_for_queue_reject_messages_after_expiration_is_reached() throws IOException, TimeoutException, InterruptedException {
            try (Connection conn = new MockConnectionFactory().newConnection()) {
                try (Channel channel = conn.createChannel()) {
                    channel.exchangeDeclare("rejected-ex", BuiltinExchangeType.FANOUT);
                    channel.queueDeclare("rejected", true, false, false, null);
                    channel.queueBindNoWait("rejected", "rejected-ex", "unused", null);
                    queue("fruits")
                        .withMessageTtl(200)
                        .withDeadLetterExchange("rejected-ex")
                        .declare(channel);

                    channel.basicPublish("", "fruits", null, "banana".getBytes());
                    TimeUnit.MILLISECONDS.sleep(400L);
                    GetResponse getResponse = channel.basicGet("fruits", true);
                    assertThat(getResponse).isNull();

                    getResponse = channel.basicGet("rejected", true);
                    assertThat(getResponse).isNotNull();
                    
                    List<Map<String,Object>> xDeathHeaders = (List<Map<String, Object>>) getResponse.getProps().getHeaders().get("x-death");
                    assertEquals(xDeathHeaders.get(0).get("reason").toString(), "expired");
                    assertEquals(xDeathHeaders.get(0).get("queue").toString(), "fruits");
                    
                }
            }
        }

        @Test
        void message_ttl_for_queue_reject_messages_after_expiration_is_reached_using_consumer() throws IOException, TimeoutException, InterruptedException {
            try (Connection conn = new MockConnectionFactory().newConnection()) {
                try (Channel channel = conn.createChannel()) {
                    queue("fruits").withMessageTtl(100).declare(channel);

                    channel.basicPublish("", "fruits", null, "banana".getBytes());
                    TimeUnit.MILLISECONDS.sleep(300L);

                    List<String> messages = new ArrayList<>();
                    channel.basicConsume("fruits", new DefaultConsumer(channel) {
                        @Override
                        public void handleDelivery(String consumerTag,
                                                   Envelope envelope,
                                                   AMQP.BasicProperties properties,
                                                   byte[] body) {
                            messages.add(new String(body));
                        }

                        @Override
                        public void handleCancelOk(String consumerTag) {
                            // Not the purpose of the test
                        }
                    });

                    TimeUnit.MILLISECONDS.sleep(120L);

                    assertThat(messages).isEmpty();
                }
            }
        }

        @Test
        void non_long_message_ttl_for_queue_is_not_used() throws IOException, TimeoutException, InterruptedException {
            try (Connection conn = new MockConnectionFactory().newConnection()) {
                try (Channel channel = conn.createChannel()) {
                    Map<String, Object> args = new HashMap<>();
                    args.put("x-message-ttl", "test");
                    channel.queueDeclare("fruits", true, false, false, args);
                    channel.basicPublish("", "fruits", null, "banana".getBytes());
                    TimeUnit.MILLISECONDS.sleep(100L);
                    GetResponse getResponse = channel.basicGet("fruits", true);
                    assertThat(getResponse).isNotNull();
                }
            }
        }

        @Test
        void message_ttl_in_publishers_reject_messages_after_expiration_is_reached() throws IOException, TimeoutException, InterruptedException {
            try (Connection conn = new MockConnectionFactory().newConnection()) {
                try (Channel channel = conn.createChannel()) {
                    channel.queueDeclare("fruits", true, false, false, null);
                    AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                        .expiration("200")
                        .build();
                    channel.basicPublish("", "fruits", properties, "banana".getBytes());
                    TimeUnit.MILLISECONDS.sleep(400L);
                    GetResponse getResponse = channel.basicGet("fruits", true);
                    assertThat(getResponse).isNull();
                }
            }
        }

        @Test
        void non_long_message_ttl_in_publishers_is_not_used() throws IOException, TimeoutException, InterruptedException {
            try (Connection conn = new MockConnectionFactory().newConnection()) {
                try (Channel channel = conn.createChannel()) {
                    channel.queueDeclare("fruits", true, false, false, null);
                    AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                        .expiration("test")
                        .build();
                    channel.basicPublish("", "fruits", properties, "banana".getBytes());
                    TimeUnit.MILLISECONDS.sleep(100L);
                    GetResponse getResponse = channel.basicGet("fruits", true);
                    assertThat(getResponse).isNotNull();
                }
            }
        }
    }

    @Nested
    class PublisherConfirms {

        @Test
        void confirmSelect_on_transactional_channel_throws() throws IOException, TimeoutException {
            try (Connection conn = new MockConnectionFactory().newConnection()) {
                try (Channel channel = conn.createChannel()) {
                    channel.txSelect();
                    assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> channel.confirmSelect())
                        .withMessage("A transactional channel cannot be put into confirm mode");
                }
            }
        }

        @Test
        void txSelect_on_confirm_channel_throws() throws IOException, TimeoutException {
            try (Connection conn = new MockConnectionFactory().newConnection()) {
                try (Channel channel = conn.createChannel()) {
                    channel.confirmSelect();
                    assertThatExceptionOfType(IllegalStateException.class)
                        .isThrownBy(() -> channel.txSelect())
                        .withMessage("Once a channel is in confirm mode, it cannot be made transactional");
                }
            }
        }

        @Test
        void getNextPublishSeqNo_returns_zero_out_of_confirm_mode() throws IOException, TimeoutException {
            try (Connection conn = new MockConnectionFactory().newConnection()) {
                try (Channel channel = conn.createChannel()) {
                    assertThat(channel.getNextPublishSeqNo()).isEqualTo(0L);
                }
            }
        }

        @Test
        void getNextPublishSeqNo_returns_the_next_message_count_in_confirm_mode() throws IOException, TimeoutException {
            try (Connection conn = new MockConnectionFactory().newConnection()) {
                try (Channel channel = conn.createChannel()) {
                    assertThat(channel.confirmSelect()).isNotNull();
                    assertThat(channel.getNextPublishSeqNo()).isEqualTo(1L);

                    channel.basicPublish("", "vegetable.carrot", null, "carrot".getBytes());

                    assertThat(channel.getNextPublishSeqNo()).isEqualTo(2L);
                }
            }
        }

        @Test
        void confirm_listeners_are_notified() throws IOException, TimeoutException {
            try (Connection conn = new MockConnectionFactory().newConnection()) {
                try (Channel channel = conn.createChannel()) {
                    assertThat(channel.confirmSelect()).isNotNull();

                    List<Long> firstConfirmedMessages = new ArrayList<>();
                    List<Long> firstUnconfirmedMessages = new ArrayList<>();
                    ConfirmListener firstListener = new ConfirmListener() {
                        @Override
                        public void handleAck(long deliveryTag, boolean multiple) {
                            firstConfirmedMessages.add(deliveryTag);
                        }

                        @Override
                        public void handleNack(long deliveryTag, boolean multiple) {
                            firstUnconfirmedMessages.add(deliveryTag);
                        }
                    };
                    channel.addConfirmListener(firstListener);
                    List<Long> secondConfirmedMessages = new ArrayList<>();
                    List<Long> secondUnconfirmedMessages = new ArrayList<>();
                    ConfirmListener secondListener = channel.addConfirmListener(
                        (deliveryTag, multiple) -> secondConfirmedMessages.add(deliveryTag),
                        (deliveryTag, multiple) -> secondUnconfirmedMessages.add(deliveryTag));

                    channel.basicPublish("", "vegetable.carrot", null, "carrot".getBytes());

                    assertThat(firstConfirmedMessages).containsExactly(1L);
                    assertThat(firstUnconfirmedMessages).isEmpty();
                    assertThat(secondConfirmedMessages).containsExactly(1L);
                    assertThat(secondUnconfirmedMessages).isEmpty();

                    assertThat(channel.removeConfirmListener(secondListener)).isTrue();

                    channel.basicPublish("", "vegetable.carrot", null, "carrot".getBytes());

                    assertThat(firstConfirmedMessages).containsExactly(1L, 2L);
                    assertThat(firstUnconfirmedMessages).isEmpty();
                    assertThat(secondConfirmedMessages).containsExactly(1L);
                    assertThat(secondUnconfirmedMessages).isEmpty();

                    channel.clearConfirmListeners();

                    channel.basicPublish("", "vegetable.carrot", null, "carrot".getBytes());

                    assertThat(firstConfirmedMessages).containsExactly(1L, 2L);
                    assertThat(firstUnconfirmedMessages).isEmpty();
                    assertThat(secondConfirmedMessages).containsExactly(1L);
                    assertThat(secondUnconfirmedMessages).isEmpty();
                }
            }
        }

        @TestFactory
        List<DynamicTest> waitForConfirms_returns_true_in_confirm_mode() {
            boolean confirmMode = true;
            return Arrays.asList(
                dynamicTest("waitForConfirms", () -> executeInChannel(confirmMode, channel ->
                    assertThat(channel.waitForConfirms()).isTrue())),
                dynamicTest("waitForConfirms(timeout)", () -> executeInChannel(confirmMode, channel ->
                    assertThat(channel.waitForConfirms(0L)).isTrue())),
                dynamicTest("waitForConfirmsOrDie", () -> executeInChannel(confirmMode, channel ->
                    channel.waitForConfirmsOrDie())),
                dynamicTest("waitForConfirmsOrDie(timeout)", () -> executeInChannel(confirmMode, channel ->
                    channel.waitForConfirmsOrDie(0L)))
            );
        }

        @TestFactory
        List<DynamicTest> waitForConfirms_throws_out_of_confirm_mode() {
            boolean confirmMode = false;
            return Arrays.asList(
                dynamicTest("waitForConfirms", () -> executeInChannel(confirmMode, channel ->
                    assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> channel.waitForConfirms()))),
                dynamicTest("waitForConfirms(timeout)", () -> executeInChannel(confirmMode, channel ->
                    assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> channel.waitForConfirms(0L)))),
                dynamicTest("waitForConfirmsOrDie", () -> executeInChannel(confirmMode, channel ->
                    assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> channel.waitForConfirmsOrDie()))),
                dynamicTest("waitForConfirmsOrDie(timeout)", () -> executeInChannel(confirmMode, channel ->
                    assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> channel.waitForConfirmsOrDie(0L))))
            );
        }

        private void executeInChannel(boolean confirmMode, java.util.function.Consumer<MockChannel> action) {
            try (MockConnection conn = new MockConnectionFactory().newConnection()) {
                try (MockChannel channel = conn.createChannel()) {
                    if (confirmMode) {
                        channel.confirmSelect();
                    }
                    action.accept(channel);
                }
            }
        }
    }

    @Nested
    class QueueLengthLimit {

        @Test
        void oldest_message_is_dropped_when_length_limit_is_reached() throws IOException {
            try (MockConnection conn = new MockConnectionFactory().newConnection()) {
                try (MockChannel channel = conn.createChannel()) {
                	channel.exchangeDeclare("rejected-ex", BuiltinExchangeType.FANOUT);
                	channel.queueDeclare("rejected", true, false, false, null);
                    channel.queueBindNoWait("rejected", "rejected-ex", "unused", null);
                    String queueName = dynamicQueue().withMaxLength(2).withDeadLetterExchange("rejected-ex").declare(channel).getQueue();

                    IntStream.range(0, 6).forEach(i ->
                        channel.basicPublish("", queueName, null, Integer.valueOf(i).toString().getBytes())
                    );

                    assertThat(channel.messageCount(""))
                        .as("Queue length")
                        .isEqualTo(2);
                    
                    assertThat(channel.messageCount("rejected"))
                    .as("Rejected Queue length")
                    .isEqualTo(4);
                    GetResponse basicGet = channel.basicGet("rejected", true);
                    
                    List<Map<String,Object>> xDeathHeaders = (List<Map<String, Object>>) basicGet.getProps().getHeaders().get("x-death");
                    assertEquals(xDeathHeaders.get(0).get("reason").toString(), "maxlen");
                    assertEquals(xDeathHeaders.get(0).get("queue").toString(),queueName);
                    
                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("4");
                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("5");
                }
            }
        }

        @Test
        void oldest_message_is_dropped_when_length_bytes_limit_is_reached() {
            try (MockConnection conn = new MockConnectionFactory().newConnection()) {
                try (MockChannel channel = conn.createChannel()) {
                    String queueName = dynamicQueue().withMaxLengthBytes(10).declare(channel).getQueue();

                    IntStream.range(0, 6).forEach(i ->
                        channel.basicPublish("", queueName, null, ("hello " + i).getBytes())
                    );

                    assertThat(channel.messageCount(""))
                        .as("Queue length")
                        .isEqualTo(2);

                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("hello 4");
                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("hello 5");
                }
            }
        }

        @Test
        void new_message_is_dropped_when_length_limit_is_reached_and_overflow_is_set_to_reject_publish() {
            try (MockConnection conn = new MockConnectionFactory().newConnection()) {
                try (MockChannel channel = conn.createChannel()) {
                    String queueName = dynamicQueue()
                        .withMaxLength(2)
                        .withOverflow(AmqArguments.Overflow.REJECT_PUBLISH)
                        .declare(channel)
                        .getQueue();

                    IntStream.range(0, 6).forEach(i ->
                        channel.basicPublish("", queueName, null, Integer.valueOf(i).toString().getBytes())
                    );

                    assertThat(channel.messageCount(""))
                        .as("Queue length")
                        .isEqualTo(2);

                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("0");
                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("1");
                }
            }
        }

        @Test
        void negative_limits_are_ignored() {
            try (MockConnection conn = new MockConnectionFactory().newConnection()) {
                try (MockChannel channel = conn.createChannel()) {
                    Map<String, Object> arguments = new HashMap<>();
                    arguments.put("x-max-length", -2L);
                    arguments.put("x-max-length-bytes", -42.0D);
                    String queueName = channel.queueDeclare("", true, true, false, arguments).getQueue();

                    IntStream.range(0, 6).forEach(i ->
                        channel.basicPublish("", queueName, null, Integer.valueOf(i).toString().getBytes())
                    );

                    assertThat(channel.messageCount(""))
                        .as("Queue length")
                        .isEqualTo(6);
                }
            }
        }
    }

    @Nested
    class PriorityQueueSupport {

        @Test
        void nominal_use() {
            try (MockConnection conn = new MockConnectionFactory().newConnection()) {
                try (MockChannel channel = conn.createChannel()) {
                    String queueName = dynamicQueue().withMaxPriority(10).declare(channel).getQueue();

                    channel.basicPublish("", queueName, new AMQP.BasicProperties.Builder().priority(2).build(), "first".getBytes());
                    channel.basicPublish("", queueName, new AMQP.BasicProperties.Builder().priority(6).build(), "second".getBytes());
                    channel.basicPublish("", queueName, new AMQP.BasicProperties.Builder().priority(4).build(), "third".getBytes());


                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("second");
                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("third");
                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("first");
                }
            }
        }

        @Test
        void no_priority_is_considered_zero() {
            try (MockConnection conn = new MockConnectionFactory().newConnection()) {
                try (MockChannel channel = conn.createChannel()) {
                    String queueName = dynamicQueue().withMaxPriority(10).declare(channel).getQueue();

                    channel.basicPublish("", queueName, null, "first".getBytes());
                    channel.basicPublish("", queueName, new AMQP.BasicProperties.Builder().priority(2).build(), "second".getBytes());


                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("second");
                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("first");
                }
            }
        }

        @TestFactory
        List<DynamicTest> borderline_cases() {
            return Arrays.asList(
                dynamicTest("higher_than_max_priority_is_considered_max", () -> withMaxPriority(10)),
                dynamicTest("max_priority_higher_than_255_is_ignored", () -> withMaxPriority(256)),
                dynamicTest("max_priority_lower_than_1_is_ignored", () -> withMaxPriority(0))
            );
        }

        private void withMaxPriority(int priority) {
            try (MockConnection conn = new MockConnectionFactory().newConnection()) {
                try (MockChannel channel = conn.createChannel()) {
                    String queueName = dynamicQueue().withMaxPriority(priority).declare(channel).getQueue();

                    channel.basicPublish("", queueName, new AMQP.BasicProperties.Builder().priority(16).build(), "first".getBytes());
                    channel.basicPublish("", queueName, new AMQP.BasicProperties.Builder().priority(18).build(), "second".getBytes());


                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("first");
                    assertThat(new String(channel.basicGet("", true).getBody())).isEqualTo("second");
                }
            }
        }
    }
}
