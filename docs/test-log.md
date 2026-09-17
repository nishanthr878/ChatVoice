### STT test throw twilio
```
2026-09-17T11:56:54.317Z  INFO 1 --- [ient-3-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram speech_final for callSid=CAe10c492f614439854dfe6e31bea44aa6: Hello. I would like the second order taken.

2026-09-17T11:56:55.103Z  INFO 1 --- [ool-13-thread-1] [                                                 ] i.n.o.adapter.voice.VoiceSessionManager  : callSid=CAe10c492f614439854dfe6e31bea44aa6 conversationId=fc6852f5-0186-4e9a-a14c-e943e6115458 step() -> response ready, len=80

2026-09-17T11:57:39.790Z  WARN 1 --- [trics-publisher] [                                                 ] i.m.registry.otlp.OtlpMeterRegistry      : Failed to publish metrics to OTLP receiver (context: url=http://localhost:4318/v1/metrics, resource-attributes={service.name=va-orchestrator})
2026-09-17T11:58:14.167Z  INFO 1 --- [nio-8080-exec-5] [                                                 ] i.n.o.adapter.voice.VoiceSessionManager  : Voice call stopped: callSid=CAe10c492f614439854dfe6e31bea44aa6 conversationId=fc6852f5-0186-4e9a-a14c-e943e6115458 totalFrames=5668

2026-09-17T11:58:14.411Z  INFO 1 --- [ient-3-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram STT closed for callSid=CAe10c492f614439854dfe6e31bea44aa6: 1000 call ended

2026-09-17T11:58:39.564Z  WARN 1 --- [trics-publisher] [                                                 ] i.m.registry.otlp.OtlpMeterRegistry      : Failed to publish metrics to OTLP receiver (context: url=http://localhost:4318/v1/metrics, resource-attributes={service.name=va-orchestrator})
```


```

2026-09-17T12:30:52.780Z  INFO 1 --- [ient-1-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram speech_final for callSid=CA4a8746da39a3f607acd80c1c0939f2a9: Hi.

2026-09-17T12:30:54.693Z  INFO 1 --- [ient-1-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram speech_final for callSid=CA4a8746da39a3f607acd80c1c0939f2a9: I would like to return it

2026-09-17T12:31:05.970Z  INFO 1 --- [ient-1-Worker-0] [ ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram speech_final for callSid=CA4a8746da39a3f607acd80c1c0939f2a9: I would like to get an order

2026-09-17T12:31:15.246Z  INFO 1 --- [nio-8080-exec-8] [                                                 ] i.n.o.adapter.voice.VoiceSessionManager  : Voice call stopped: callSid=CA4a8746da39a3f607acd80c1c0939f2a9 conversationId=a9caae63-2d90-4780-8099-33e19b0bfe12 totalFrames=1721

2026-09-17T12:31:15.509Z  INFO 1 --- [ient-1-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram STT closed for callSid=CA4a8746da39a3f607acd80c1c0939f2a9: 1000 call ended

2026-09-17T12:31:15.521Z  INFO 1 --- [ient-2-Worker-1] [                                                 ] i.n.o.adapter.voice.DeepgramTtsClient    : Deepgram TTS closed for callSid=CA4a8746da39a3f607acd80c1c0939f2a9: 1006 


```


```
2026-09-17T12:33:22.473Z  INFO 1 --- [ient-3-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram speech_final for callSid=CAb66659c54ec552f73ec29c2bf9f22b4f: Hello?

2026-09-17T12:33:33.164Z  INFO 1 --- [ient-3-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram speech_final for callSid=CAb66659c54ec552f73ec29c2bf9f22b4f: I would like to get an order details for an order

2026-09-17T12:33:41.788Z  INFO 1 --- [ient-3-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram speech_final for callSid=CAb66659c54ec552f73ec29c2bf9f22b4f: One zero zero one

2026-09-17T12:34:11.444Z  INFO 1 --- [ient-3-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram speech_final for callSid=CAb66659c54ec552f73ec29c2bf9f22b4f: Okay. Thank you.

2026-09-17T12:34:12.957Z  INFO 1 --- [ient-3-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram speech_final for callSid=CAb66659c54ec552f73ec29c2bf9f22b4f: Can you check for an another order?

2026-09-17T12:34:30.092Z  INFO 1 --- [ient-3-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram speech_final for callSid=CAb66659c54ec552f73ec29c2bf9f22b4f: Call recording has now.

2026-09-17T12:34:31.852Z  INFO 1 --- [io-8080-exec-10] [                                                 ] i.n.o.adapter.voice.VoiceSessionManager  : Voice call stopped: callSid=CAb66659c54ec552f73ec29c2bf9f22b4f conversationId=40e1438d-1126-4d80-af53-5028c0003e03 totalFrames=4897

2026-09-17T12:34:32.153Z  INFO 1 --- [ient-3-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramSttClient    : Deepgram STT closed for callSid=CAb66659c54ec552f73ec29c2bf9f22b4f: 1000 call ended

2026-09-17T12:34:32.168Z  INFO 1 --- [ient-4-Worker-0] [                                                 ] i.n.o.adapter.voice.DeepgramTtsClient    : Deepgram TTS closed for callSid=CAb66659c54ec552f73ec29c2bf9f22b4f: 1006 

```