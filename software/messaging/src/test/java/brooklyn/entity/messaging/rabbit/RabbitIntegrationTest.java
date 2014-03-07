package brooklyn.entity.messaging.rabbit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.messaging.MessageBroker;
import brooklyn.entity.messaging.amqp.AmqpExchange;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.trait.Startable;
import brooklyn.location.Location;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.test.EntityTestUtils;
import brooklyn.test.entity.TestApplication;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.QueueingConsumer;

/**
 * Test the operation of the {@link RabbitBroker} class.
 * 
 * TODO If you're having problems running this test successfully, here are a few tips:
 * 
 *  - Is `erl` on your path for a non-interactive ssh session?
 *    Look in rabbit's $RUN_DIR/console-err.log (e.g. /tmp/brooklyn-aled/apps/someappid/entities/RabbitBroker_2.8.7_JROYTcSL/console-err.log)
 *    I worked around that by adding to my ~/.brooklyn/brooklyn.properties:
 *      brooklyn.ssh.config.scriptHeader=#!/bin/bash -e\nif [ -f ~/.bashrc ] ; then . ~/.bashrc ; fi\nif [ -f ~/.profile ] ; then . ~/.profile ; fi\necho $PATH > /tmp/mypath.txt
 *    
 *  - Is the hostname resolving properly?
 *    Look in $RUN_DIR/console-out.log; is there a message like:
 *      ERROR: epmd error for host "Aleds-MacBook-Pro": timeout (timed out establishing tcp connection)
 *    I got around that with disabling my wifi and running when not connected to the internet.
 */
public class RabbitIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(RabbitIntegrationTest.class);

    private TestApplication app;
    private Location testLocation;
    private RabbitBroker rabbit;

    @BeforeMethod(groups = "Integration")
    public void setup() {
        app = ApplicationBuilder.newManagedApp(TestApplication.class);
        testLocation = new LocalhostMachineProvisioningLocation();
    }

    @AfterMethod(alwaysRun = true)
    public void shutdown() {
        if (app != null) Entities.destroyAll(app.getManagementContext());
    }

    /**
     * Test that the broker starts up and sets SERVICE_UP correctly.
     */
    @Test(groups = "Integration")
    public void canStartupAndShutdown() throws Exception {
        rabbit = app.createAndManageChild(EntitySpec.create(RabbitBroker.class));
        rabbit.start(ImmutableList.of(testLocation));
        EntityTestUtils.assertAttributeEqualsEventually(rabbit, Startable.SERVICE_UP, true);
        rabbit.stop();
        assertFalse(rabbit.getAttribute(Startable.SERVICE_UP));
    }

    /**
     * Test that an AMQP client can connect to and use the broker.
     */
    @Test(groups = "Integration")
    public void testClientConnection() throws Exception {
        rabbit = app.createAndManageChild(EntitySpec.create(RabbitBroker.class));
        rabbit.start(ImmutableList.of(testLocation));
        EntityTestUtils.assertAttributeEqualsEventually(rabbit, Startable.SERVICE_UP, true);

        byte[] content = "MessageBody".getBytes(Charsets.UTF_8);
        String queue = "queueName";
        Channel producer = null;
        Channel consumer = null;
        try {
	        producer = getAmqpChannel(rabbit);
	        consumer = getAmqpChannel(rabbit);

	        producer.queueDeclare(queue, true, false, false, ImmutableMap.<String,Object>of());
	        producer.queueBind(queue, AmqpExchange.DIRECT, queue);
	        producer.basicPublish(AmqpExchange.DIRECT, queue, null, content);
            
            QueueingConsumer queueConsumer = new QueueingConsumer(consumer);
            consumer.basicConsume(queue, true, queueConsumer);
        
            QueueingConsumer.Delivery delivery = queueConsumer.nextDelivery(60 * 1000l); // one minute timeout
            assertEquals(delivery.getBody(), content);
        } finally {
            if (producer != null) producer.close();
            if (consumer != null) consumer.close();
        }
    }
    
    private Channel getAmqpChannel(RabbitBroker rabbit) throws Exception {
        String uri = rabbit.getAttribute(MessageBroker.BROKER_URL);
        log.warn("connecting to rabbit {}", uri);
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(uri);
        Connection conn = factory.newConnection();
        Channel channel = conn.createChannel();
        return channel;
    }
}