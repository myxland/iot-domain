
package com.gihub.surpassm.iot.mqtt.protocol;

import com.gihub.surpassm.iot.mqtt.internal.InternalCommunication;
import com.gihub.surpassm.iot.mqtt.internal.InternalMessage;
import com.gihub.surpassm.iot.mqtt.pojo.DupPublishMessageStore;
import com.gihub.surpassm.iot.mqtt.pojo.RetainMessageStore;
import com.gihub.surpassm.iot.mqtt.pojo.SubscribeStore;
import com.gihub.surpassm.iot.mqtt.service.*;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.mqtt.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * PUBLISH连接处理
 */
public class Publish {

	private static final Logger LOGGER = LoggerFactory.getLogger(Publish.class);

	private ISessionStoreService sessionStoreService;

	private ISubscribeStoreService subscribeStoreService;

	private IMessageIdService messageIdService;

	private IRetainMessageStoreService retainMessageStoreService;

	private IDupPublishMessageStoreService dupPublishMessageStoreService;

	private InternalCommunication internalCommunication;

	public Publish(ISessionStoreService sessionStoreService, ISubscribeStoreService subscribeStoreService, IMessageIdService messageIdService, IRetainMessageStoreService retainMessageStoreService, IDupPublishMessageStoreService dupPublishMessageStoreService, InternalCommunication internalCommunication) {
		this.sessionStoreService = sessionStoreService;
		this.subscribeStoreService = subscribeStoreService;
		this.messageIdService = messageIdService;
		this.retainMessageStoreService = retainMessageStoreService;
		this.dupPublishMessageStoreService = dupPublishMessageStoreService;
		this.internalCommunication = internalCommunication;
	}

	/**
	 * 	QoS值	Bit 2	Bit 1	描述
	 * 	0		0		0	最多分发一次
	 * 	1		0		1	至少分发一次
	 * 	2		1		0	只分发一次
	 * 	-		1		1	保留位
	 */
	public void processPublish(Channel channel, MqttPublishMessage msg) {
		// QoS=0
		if (msg.fixedHeader().qosLevel() == MqttQoS.AT_MOST_ONCE) {
			byte[] messageBytes = new byte[msg.payload().readableBytes()];
			msg.payload().getBytes(msg.payload().readerIndex(), messageBytes);
			InternalMessage internalMessage = new InternalMessage().setTopic(msg.variableHeader().topicName())
				.setMqttQoS(msg.fixedHeader().qosLevel().value()).setMessageBytes(messageBytes)
				.setDup(false).setRetain(false);
			internalCommunication.internalSend(internalMessage);
			this.sendPublishMessage(msg.variableHeader().topicName(), msg.fixedHeader().qosLevel(), messageBytes, false, false);
		}
		// QoS=1
		if (msg.fixedHeader().qosLevel() == MqttQoS.AT_LEAST_ONCE) {
			byte[] messageBytes = new byte[msg.payload().readableBytes()];
			msg.payload().getBytes(msg.payload().readerIndex(), messageBytes);
			InternalMessage internalMessage = new InternalMessage().setTopic(msg.variableHeader().topicName())
				.setMqttQoS(msg.fixedHeader().qosLevel().value()).setMessageBytes(messageBytes)
				.setDup(false).setRetain(false);
			internalCommunication.internalSend(internalMessage);
			this.sendPublishMessage(msg.variableHeader().topicName(), msg.fixedHeader().qosLevel(), messageBytes, false, false);
			this.sendPubAckMessage(channel, msg.variableHeader().packetId());
		}
		// QoS=2
		if (msg.fixedHeader().qosLevel() == MqttQoS.EXACTLY_ONCE) {
			byte[] messageBytes = new byte[msg.payload().readableBytes()];
			msg.payload().getBytes(msg.payload().readerIndex(), messageBytes);
			InternalMessage internalMessage = new InternalMessage().setTopic(msg.variableHeader().topicName())
				.setMqttQoS(msg.fixedHeader().qosLevel().value()).setMessageBytes(messageBytes)
				.setDup(false).setRetain(false);
			internalCommunication.internalSend(internalMessage);
			this.sendPublishMessage(msg.variableHeader().topicName(), msg.fixedHeader().qosLevel(), messageBytes, false, false);
			this.sendPubRecMessage(channel, msg.variableHeader().packetId());
		}
		// retain=1, 保留消息
		if (msg.fixedHeader().isRetain()) {
			byte[] messageBytes = new byte[msg.payload().readableBytes()];
			msg.payload().getBytes(msg.payload().readerIndex(), messageBytes);
			if (messageBytes.length == 0) {
				retainMessageStoreService.remove(msg.variableHeader().topicName());
			} else {
				RetainMessageStore retainMessageStore = new RetainMessageStore().setTopic(msg.variableHeader().topicName()).setMqttQoS(msg.fixedHeader().qosLevel().value())
					.setMessageBytes(messageBytes);
				retainMessageStoreService.put(msg.variableHeader().topicName(), retainMessageStore);
			}
		}
	}

	private void sendPublishMessage(String topic, MqttQoS mqttQoS, byte[] messageBytes, boolean retain, boolean dup) {
		List<SubscribeStore> subscribeStores = subscribeStoreService.search(topic);
		subscribeStores.forEach(subscribeStore -> {
			if (sessionStoreService.containsKey(subscribeStore.getClientId())) {
				// 订阅者收到MQTT消息的QoS级别, 最终取决于发布消息的QoS和主题订阅的QoS
				MqttQoS respQoS = mqttQoS.value() > subscribeStore.getMqttQoS() ? MqttQoS.valueOf(subscribeStore.getMqttQoS()) : mqttQoS;
				if (respQoS == MqttQoS.AT_MOST_ONCE) {
					MqttPublishMessage publishMessage = (MqttPublishMessage) MqttMessageFactory.newMessage(
						new MqttFixedHeader(MqttMessageType.PUBLISH, dup, respQoS, retain, 0),
						new MqttPublishVariableHeader(topic, 0), Unpooled.buffer().writeBytes(messageBytes));
					LOGGER.debug("PUBLISH - clientId: {}, topic: {}, Qos: {}", subscribeStore.getClientId(), topic, respQoS.value());
					sessionStoreService.get(subscribeStore.getClientId()).getChannel().writeAndFlush(publishMessage);
				}
				if (respQoS == MqttQoS.AT_LEAST_ONCE) {
					int messageId = messageIdService.getNextMessageId();
					MqttPublishMessage publishMessage = (MqttPublishMessage) MqttMessageFactory.newMessage(
						new MqttFixedHeader(MqttMessageType.PUBLISH, dup, respQoS, retain, 0),
						new MqttPublishVariableHeader(topic, messageId), Unpooled.buffer().writeBytes(messageBytes));
					LOGGER.debug("PUBLISH - clientId: {}, topic: {}, Qos: {}, messageId: {}", subscribeStore.getClientId(), topic, respQoS.value(), messageId);
					DupPublishMessageStore dupPublishMessageStore = new DupPublishMessageStore().setClientId(subscribeStore.getClientId())
						.setTopic(topic).setMqttQoS(respQoS.value()).setMessageBytes(messageBytes);
					dupPublishMessageStoreService.put(subscribeStore.getClientId(), dupPublishMessageStore);
					sessionStoreService.get(subscribeStore.getClientId()).getChannel().writeAndFlush(publishMessage);
				}
				if (respQoS == MqttQoS.EXACTLY_ONCE) {
					int messageId = messageIdService.getNextMessageId();
					MqttPublishMessage publishMessage = (MqttPublishMessage) MqttMessageFactory.newMessage(
						new MqttFixedHeader(MqttMessageType.PUBLISH, dup, respQoS, retain, 0),
						new MqttPublishVariableHeader(topic, messageId), Unpooled.buffer().writeBytes(messageBytes));
					LOGGER.debug("PUBLISH - clientId: {}, topic: {}, Qos: {}, messageId: {}", subscribeStore.getClientId(), topic, respQoS.value(), messageId);
					DupPublishMessageStore dupPublishMessageStore = new DupPublishMessageStore().setClientId(subscribeStore.getClientId())
						.setTopic(topic).setMqttQoS(respQoS.value()).setMessageBytes(messageBytes);
					dupPublishMessageStoreService.put(subscribeStore.getClientId(), dupPublishMessageStore);
					sessionStoreService.get(subscribeStore.getClientId()).getChannel().writeAndFlush(publishMessage);
				}
			}
		});
	}

	private void sendPubAckMessage(Channel channel, int messageId) {
		MqttPubAckMessage pubAckMessage = (MqttPubAckMessage) MqttMessageFactory.newMessage(
			new MqttFixedHeader(MqttMessageType.PUBACK, false, MqttQoS.AT_MOST_ONCE, false, 0),
			MqttMessageIdVariableHeader.from(messageId), null);
		channel.writeAndFlush(pubAckMessage);
	}

	private void sendPubRecMessage(Channel channel, int messageId) {
		MqttMessage pubRecMessage = MqttMessageFactory.newMessage(
			new MqttFixedHeader(MqttMessageType.PUBREC, false, MqttQoS.AT_MOST_ONCE, false, 0),
			MqttMessageIdVariableHeader.from(messageId), null);
		channel.writeAndFlush(pubRecMessage);
	}

}
