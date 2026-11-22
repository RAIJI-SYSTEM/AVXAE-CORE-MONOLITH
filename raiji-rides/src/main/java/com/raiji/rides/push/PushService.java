package com.raiji.rides.push;

import java.util.Collection;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Manda um push para todos os aparelhos de cada pessoa, fora da thread da requisição. */
@Service
public class PushService {

    private final PushDeviceRepository devices;
    private final FcmSender fcm;

    public PushService(PushDeviceRepository devices, FcmSender fcm) {
        this.devices = devices;
        this.fcm = fcm;
    }

    @Async
    public void send(Collection<String> userSubs, PushMessage message) {
        if (!fcm.enabled()) return;
        for (String sub : userSubs) {
            for (String token : devices.tokensOf(sub)) {
                if (fcm.send(token, message) == FcmSender.Result.INVALID_TOKEN) {
                    devices.deleteInvalid(token);
                }
            }
        }
    }
}
