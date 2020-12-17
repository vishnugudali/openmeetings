/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 */
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") +  you may not use this file except in compliance
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
package org.apache.openmeetings.core.remote;

import static java.util.UUID.randomUUID;
import static java.util.concurrent.CompletableFuture.delayedExecutor;
import static org.apache.openmeetings.core.remote.KurentoHandler.PARAM_CANDIDATE;
import static org.apache.openmeetings.core.remote.KurentoHandler.PARAM_ICE;
import static org.apache.openmeetings.core.remote.KurentoHandler.TAG_ROOM;
import static org.apache.openmeetings.core.remote.KurentoHandler.TAG_STREAM_UID;
import static org.apache.openmeetings.core.remote.KurentoHandler.getFlowoutTimeout;
import static org.apache.openmeetings.core.remote.KurentoHandler.newKurentoMsg;
import static org.apache.openmeetings.util.OmFileHelper.getRecUri;
import static org.apache.openmeetings.util.OmFileHelper.getRecordingChunk;

import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.apache.openmeetings.core.sip.ISipCallbacks;
import org.apache.openmeetings.core.sip.SipStackProcessor;
import org.apache.openmeetings.core.util.WebSocketHelper;
import org.apache.openmeetings.db.entity.basic.Client;
import org.apache.openmeetings.db.entity.basic.Client.Activity;
import org.apache.openmeetings.db.entity.basic.Client.StreamDesc;
import org.apache.openmeetings.db.entity.basic.Client.StreamType;
import org.apache.openmeetings.db.entity.record.RecordingChunk.Type;
import org.apache.openmeetings.db.util.ws.RoomMessage;
import org.apache.openmeetings.db.util.ws.TextRoomMessage;
import org.apache.openmeetings.util.OmFileHelper;
import org.kurento.client.Continuation;
import org.kurento.client.IceCandidate;
import org.kurento.client.ListenerSubscription;
import org.kurento.client.MediaFlowState;
import org.kurento.client.MediaObject;
import org.kurento.client.MediaPipeline;
import org.kurento.client.MediaProfileSpecType;
import org.kurento.client.MediaType;
import org.kurento.client.RecorderEndpoint;
import org.kurento.client.RtpEndpoint;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.openjson.JSONObject;

public class KStream extends AbstractStream implements ISipCallbacks {
	private static final Logger log = LoggerFactory.getLogger(KStream.class);

	private final KurentoHandler kHandler;
	private final KRoom kRoom;
	private final Date connectedSince;
	private final StreamType streamType;
	private MediaProfileSpecType profile;
	private MediaPipeline pipeline;
	private RecorderEndpoint recorder;
	private WebRtcEndpoint outgoingMedia = null;
	private RtpEndpoint rtpEndpoint;
	private Optional<SipStackProcessor> sipProcessor = Optional.empty();
	private final ConcurrentMap<String, WebRtcEndpoint> listeners = new ConcurrentHashMap<>();
	private Optional<CompletableFuture<Object>> flowoutFuture = Optional.empty();
	private ListenerSubscription flowoutSubscription;
	private Long chunkId;
	private Type type;
	private boolean hasAudio;
	private boolean hasVideo;
	private boolean hasScreen;
	private boolean sipClient;

	public KStream(final StreamDesc sd, KRoom kRoom, KurentoHandler kHandler) {
		super(sd.getSid(), sd.getUid());
		this.kRoom = kRoom;
		streamType = sd.getType();
		this.connectedSince = new Date();
		this.kHandler = kHandler;
		//TODO Min/MaxVideoSendBandwidth
		//TODO Min/Max Audio/Video RecvBandwidth
	}

	public void startBroadcast(final StreamDesc sd, final String sdpOffer, Runnable then) {
		if (outgoingMedia != null) {
			release(false);
		}
		hasAudio = sd.hasActivity(Activity.AUDIO);
		hasVideo = sd.hasActivity(Activity.VIDEO);
		hasScreen = sd.hasActivity(Activity.SCREEN);
		sipClient = OmFileHelper.SIP_USER_ID.equals(sd.getClient().getUserId());
		if ((sdpOffer.indexOf("m=audio") > -1 && !hasAudio)
				|| (sdpOffer.indexOf("m=video") > -1 && !hasVideo && StreamType.SCREEN != streamType))
		{
			log.warn("Broadcast started without enough rights");
			return;
		}
		if (StreamType.SCREEN == streamType) {
			type = Type.SCREEN;
		} else {
			if (hasAudio && hasVideo) {
				type = Type.AUDIO_VIDEO;
			} else if (hasVideo) {
				type = Type.VIDEO_ONLY;
			} else {
				type = Type.AUDIO_ONLY;
			}
		}
		switch (type) {
			case AUDIO_VIDEO:
				profile = MediaProfileSpecType.WEBM;
				break;
			case AUDIO_ONLY:
				profile = MediaProfileSpecType.WEBM_AUDIO_ONLY;
				break;
			case SCREEN:
			case VIDEO_ONLY:
			default:
				profile = MediaProfileSpecType.WEBM_VIDEO_ONLY;
				break;
		}
		pipeline = kHandler.createPipiline(Map.of(TAG_ROOM, String.valueOf(getRoomId()), TAG_STREAM_UID, sd.getUid()), new Continuation<Void>() {
			@Override
			public void onSuccess(Void result) throws Exception {
				internalStartBroadcast(sd, sdpOffer);
				then.run();
			}

			@Override
			public void onError(Throwable cause) throws Exception {
				log.warn("Unable to create pipeline {}", KStream.this.uid, cause);
			}
		});
	}

	private void internalStartBroadcast(final StreamDesc sd, final String sdpOffer) throws Exception {
		outgoingMedia = createEndpoint(sd.getSid(), sd.getUid());
		outgoingMedia.addMediaSessionTerminatedListener(evt -> log.warn("Media stream terminated {}", sd));
		flowoutSubscription = outgoingMedia.addMediaFlowOutStateChangeListener(evt -> {
			log.info("Media Flow STATE :: {}, type {}, evt {}", evt.getState(), evt.getType(), evt.getMediaType());
			if (MediaFlowState.NOT_FLOWING == evt.getState()) {
				log.warn("FlowOut Future is created");
				flowoutFuture = Optional.of(new CompletableFuture<>().completeAsync(() -> {
					log.warn("KStream will be dropped {}", sd);
					if (StreamType.SCREEN == streamType) {
						kHandler.getStreamProcessor().doStopSharing(sid, uid);
					}
					stopBroadcast();
					return null;
				}, delayedExecutor(getFlowoutTimeout(), TimeUnit.SECONDS)));
			} else {
				dropFlowoutFuture();
			}
		});
		outgoingMedia.addMediaFlowInStateChangeListener(evt -> log.warn("Media FlowIn :: {}", evt));
		addListener(sd.getSid(), sd.getUid(), sdpOffer);
		addSipProcessor(kRoom.getSipCount());
		if (kRoom.isRecording()) {
			startRecord();
		}
		Client c = sd.getClient();
		WebSocketHelper.sendRoom(new TextRoomMessage(c.getRoomId(), c, RoomMessage.Type.RIGHT_UPDATED, c.getUid()));
		if (hasAudio || hasVideo || hasScreen) {
			WebSocketHelper.sendRoomOthers(getRoomId(), c.getUid(), newKurentoMsg()
					.put("id", "newStream")
					.put(PARAM_ICE, kHandler.getTurnServers(c))
					.put("stream", sd.toJson()));
		}
	}

	public void broadcastRestarted() {
		if (outgoingMedia != null && flowoutSubscription != null) {
			outgoingMedia.removeMediaFlowOutStateChangeListener(flowoutSubscription);
		}
		dropFlowoutFuture();
	}

	private void dropFlowoutFuture() {
		flowoutFuture.ifPresent(f -> {
			log.warn("FlowOut Future is canceled");
			f.cancel(true);
			flowoutFuture = Optional.empty();
		});
	}

	public void addListener(String sid, String uid, String sdpOffer) {
		final boolean self = uid.equals(this.uid);
		log.info("USER {}: have started {} in kRoom {}", uid, self ? "broadcasting" : "receiving", getRoomId());
		log.trace("USER {}: SdpOffer is {}", uid, sdpOffer);
		if (!self && outgoingMedia == null) {
			log.warn("Trying to add listener too early");
			return;
		}

		final WebRtcEndpoint endpoint = getEndpointForUser(sid, uid);
		final String sdpAnswer = endpoint.processOffer(sdpOffer);

		log.debug("gather candidates");
		endpoint.gatherCandidates(); // this one might throw Exception
		log.trace("USER {}: SdpAnswer is {}", this.uid, sdpAnswer);
		kHandler.sendClient(sid, newKurentoMsg()
				.put("id", "videoResponse")
				.put("uid", this.uid)
				.put("sdpAnswer", sdpAnswer));
	}

	private WebRtcEndpoint getEndpointForUser(String sid, String uid) {
		if (uid.equals(this.uid)) {
			log.debug("PARTICIPANT {}: configuring loopback", this.uid);
			return outgoingMedia;
		}

		log.debug("PARTICIPANT {}: receiving video from {}", uid, this.uid);
		WebRtcEndpoint listener = listeners.remove(uid);
		if (listener != null) {
			log.debug("PARTICIPANT {}: re-started video receiving, will drop previous endpoint", uid);
			listener.release();
		}
		log.debug("PARTICIPANT {}: creating new endpoint for {}", uid, this.uid);
		listener = createEndpoint(sid, uid);
		listeners.put(uid, listener);

		log.debug("PARTICIPANT {}: obtained endpoint for {}", uid, this.uid);
		Client cur = kHandler.getStreamProcessor().getBySid(this.sid);
		if (cur == null) {
			log.warn("Client for endpoint dooesn't exists");
		} else {
			StreamDesc sd = cur.getStream(this.uid);
			if (sd == null) {
				log.warn("Stream for endpoint dooesn't exists");
			} else {
				if (sd.hasActivity(Activity.AUDIO)) {
					outgoingMedia.connect(listener, MediaType.AUDIO);
				}
				if (StreamType.SCREEN == streamType || sd.hasActivity(Activity.VIDEO)) {
					outgoingMedia.connect(listener, MediaType.VIDEO);
				}
			}
		}
		return listener;
	}

	private void setTags(MediaObject endpoint, String uid) {
		endpoint.addTag("outUid", this.uid);
		endpoint.addTag("uid", uid);
	}

	private RtpEndpoint getRtpEndpoint(MediaPipeline pipeline) {
		RtpEndpoint endpoint = new RtpEndpoint.Builder(pipeline).build();
		setTags(endpoint, uid);
		return endpoint;
	}

	private WebRtcEndpoint createEndpoint(String sid, String uid) {
		WebRtcEndpoint endpoint = createWebRtcEndpoint(pipeline);
		setTags(endpoint, uid);

		endpoint.addIceCandidateFoundListener(evt -> kHandler.sendClient(sid
				, newKurentoMsg()
					.put("id", "iceCandidate")
					.put("uid", KStream.this.uid)
					.put(PARAM_CANDIDATE, convert(JsonUtils.toJsonObject(evt.getCandidate()))))
				);
		return endpoint;
	}

	public void startRecord() {
		log.debug("startRecord outMedia OK ? {}", outgoingMedia != null);
		if (outgoingMedia == null) {
			release(true);
			return;
		}
		final String chunkUid = "rec_" + kRoom.getRecordingId() + "_" + randomUUID();
		recorder = createRecorderEndpoint(pipeline, getRecUri(getRecordingChunk(getRoomId(), chunkUid)), profile);
		setTags(recorder, uid);

		recorder.addRecordingListener(evt -> chunkId = kRoom.getChunkDao().start(kRoom.getRecordingId(), type, chunkUid, sid));
		recorder.addStoppedListener(evt -> kRoom.getChunkDao().stop(chunkId));
		switch (profile) {
			case WEBM:
				outgoingMedia.connect(recorder, MediaType.AUDIO);
				outgoingMedia.connect(recorder, MediaType.VIDEO);
				break;
			case WEBM_VIDEO_ONLY:
				outgoingMedia.connect(recorder, MediaType.VIDEO);
				break;
			case WEBM_AUDIO_ONLY:
			default:
				outgoingMedia.connect(recorder, MediaType.AUDIO);
				break;
		}
		recorder.record(new Continuation<Void>() {
			@Override
			public void onSuccess(Void result) throws Exception {
				log.info("Recording started successfully");
			}

			@Override
			public void onError(Throwable cause) throws Exception {
				log.error("Failed to start recording", cause);
			}
		});
	}

	public void stopRecord() {
		releaseRecorder(true);
		chunkId = null;
	}

	public void remove(final Client c) {
		WebRtcEndpoint point = listeners.remove(c.getUid());
		if (point != null) {
			point.release();
		}
	}

	public void stopBroadcast() {
		kRoom.onStopBroadcast(this);
	}

	public void pauseSharing() {
		releaseListeners();
	}

	private void releaseListeners() {
		log.debug("PARTICIPANT {}: Releasing listeners", uid);
		for (Entry<String, WebRtcEndpoint> entry : listeners.entrySet()) {
			final String inUid = entry.getKey();
			log.trace("PARTICIPANT {}: Released incoming EP for {}", uid, inUid);

			final WebRtcEndpoint ep = entry.getValue();
			outgoingMedia.disconnect(ep, new Continuation<Void>() {
				@Override
				public void onSuccess(Void result) throws Exception {
					log.trace("PARTICIPANT {}: Disconnected successfully incoming EP for {}", KStream.this.uid, inUid);
				}

				@Override
				public void onError(Throwable cause) throws Exception {
					log.warn("PARTICIPANT {}: Could not disconnect incoming EP for {}", KStream.this.uid, inUid);
				}
			});
			ep.release(new Continuation<Void>() {
				@Override
				public void onSuccess(Void result) throws Exception {
					log.trace("PARTICIPANT {}: Released successfully incoming EP for {}", KStream.this.uid, inUid);
				}

				@Override
				public void onError(Throwable cause) throws Exception {
					log.warn("PARTICIPANT {}: Could not release incoming EP for {}", KStream.this.uid, inUid);
				}
			});
		}
		listeners.clear();
	}

	@Override
	public void release(boolean remove) {
		if (outgoingMedia != null) {
			releaseListeners();
			releaseRecorder(false);
			releaseRtp();
			outgoingMedia.release(new Continuation<Void>() {
				@Override
				public void onSuccess(Void result) throws Exception {
					log.trace("PARTICIPANT {}: Released successfully", KStream.this.uid);
				}

				@Override
				public void onError(Throwable cause) throws Exception {
					log.warn("PARTICIPANT {}: Could not release", KStream.this.uid, cause);
				}
			});
			pipeline.release(new Continuation<Void>() {
				@Override
				public void onSuccess(Void result) throws Exception {
					log.trace("PARTICIPANT {}: Released Pipeline", KStream.this.uid);
				}

				@Override
				public void onError(Throwable cause) throws Exception {
					log.warn("PARTICIPANT {}: Could not release Pipeline", KStream.this.uid, cause);
				}
			});
			outgoingMedia = null;
		}
		if (remove) {
			kHandler.getStreamProcessor().release(this, false);
		}
	}

	private void releaseRecorder(boolean wait) {
		if (recorder != null) {
			if (wait) {
				recorder.stopAndWait();
			} else {
				recorder.stop(new Continuation<Void>() {
					@Override
					public void onSuccess(Void result) throws Exception {
						log.trace("PARTICIPANT {}: Recording stopped", KStream.this.uid);
					}

					@Override
					public void onError(Throwable cause) throws Exception {
						log.warn("PARTICIPANT {}: Could not stop recording", KStream.this.uid, cause);
					}
				});
			}
			outgoingMedia.disconnect(recorder, new Continuation<Void>() {
				@Override
				public void onSuccess(Void result) throws Exception {
					log.trace("PARTICIPANT {}: Recorder disconnected successfully", KStream.this.uid);
				}

				@Override
				public void onError(Throwable cause) throws Exception {
					log.warn("PARTICIPANT {}: Could not disconnect recorder", KStream.this.uid, cause);
				}
			});
			recorder.release(new Continuation<Void>() {
				@Override
				public void onSuccess(Void result) throws Exception {
					log.trace("PARTICIPANT {}: Recorder released successfully", KStream.this.uid);
				}

				@Override
				public void onError(Throwable cause) throws Exception {
					log.warn("PARTICIPANT {}: Could not release recorder", KStream.this.uid, cause);
				}
			});
			recorder = null;
		}
	}

	private void releaseRtp() {
		if (rtpEndpoint != null) {
			rtpEndpoint.release(new Continuation<Void>() {
				@Override
				public void onSuccess(Void result) throws Exception {
					log.trace("PARTICIPANT {}: RtpEndpoint released successfully", KStream.this.uid);
				}

				@Override
				public void onError(Throwable cause) throws Exception {
					log.warn("PARTICIPANT {}: Could not release RtpEndpoint", KStream.this.uid, cause);
				}
			});
			rtpEndpoint = null;
		}
		sipProcessor.ifPresent(SipStackProcessor::destroy);
		sipProcessor = Optional.empty();
	}

	public void addCandidate(IceCandidate candidate, String uid) {
		if (this.uid.equals(uid)) {
			if (outgoingMedia == null) {
				return;
			}
			outgoingMedia.addIceCandidate(candidate);
		} else {
			WebRtcEndpoint endpoint = listeners.get(uid);
			log.debug("Add candidate for {}, listener found ? {}", uid, endpoint != null);
			if (endpoint != null) {
				endpoint.addIceCandidate(candidate);
			}
		}
	}

	void addSipProcessor(long count) {
		if (count > 0) {
			if (sipProcessor.isEmpty()) {
				try {
					sipProcessor = kHandler.getSipManager().createSipStackProcessor(
							randomUUID().toString()
							, kRoom.getRoom()
							, this);
					sipProcessor.ifPresent(SipStackProcessor::register);
				} catch (Exception e) {
					log.error("Unexpected error while creating SipProcessor", e);
				}
			}
		} else {
			releaseRtp();
		}
	}

	private static JSONObject convert(com.google.gson.JsonObject o) {
		return new JSONObject(o.toString());
	}

	@Override
	public String getSid() {
		return sid;
	}

	@Override
	public String getUid() {
		return uid;
	}

	public Date getConnectedSince() {
		return connectedSince;
	}

	public KRoom getKRoom() {
		return kRoom;
	}

	public Long getRoomId() {
		return kRoom.getRoom().getId();
	}

	MediaPipeline getPipeline() {
		return pipeline;
	}

	public StreamType getStreamType() {
		return streamType;
	}

	public MediaProfileSpecType getProfile() {
		return profile;
	}

	public RecorderEndpoint getRecorder() {
		return recorder;
	}

	public WebRtcEndpoint getOutgoingMedia() {
		return outgoingMedia;
	}

	public Long getChunkId() {
		return chunkId;
	}

	public Type getType() {
		return type;
	}

	public boolean contains(String uid) {
		return this.uid.equals(uid) || listeners.containsKey(uid);
	}

	@Override
	public String toString() {
		return "KStream [kRoom=" + kRoom + ", streamType=" + streamType + ", profile=" + profile + ", recorder="
				+ recorder + ", outgoingMedia=" + outgoingMedia + ", listeners=" + listeners + ", flowoutFuture="
				+ flowoutFuture + ", chunkId=" + chunkId + ", type=" + type + ", sid=" + sid + ", uid=" + uid + "]";
	}

	@Override
	public void onRegisterOk() {
		if (sipClient) {

		} else {
			rtpEndpoint = getRtpEndpoint(pipeline);
			if (hasAudio) {
				outgoingMedia.connect(rtpEndpoint, MediaType.AUDIO);
			}
			if (hasVideo) {
				outgoingMedia.connect(rtpEndpoint, MediaType.VIDEO);
			}
			sipProcessor.get().invite(kRoom.getRoom(), rtpEndpoint.generateOffer());
		}
	}

	@Override
	public void onInviteOk(String sdp) {
		if (sipClient) {

		} else {
			rtpEndpoint.processAnswer(sdp);
		}
	}
}
