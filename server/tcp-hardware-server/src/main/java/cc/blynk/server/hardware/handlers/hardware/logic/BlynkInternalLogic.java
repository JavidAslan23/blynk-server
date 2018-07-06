package cc.blynk.server.hardware.handlers.hardware.logic;

import cc.blynk.server.Holder;
import cc.blynk.server.core.dao.ota.OTAManager;
import cc.blynk.server.core.model.device.HardwareInfo;
import cc.blynk.server.core.model.widgets.others.rtc.RTC;
import cc.blynk.server.core.protocol.model.messages.StringMessage;
import cc.blynk.server.core.session.HardwareStateHolder;
import cc.blynk.utils.StringUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static cc.blynk.server.core.protocol.enums.Command.BLYNK_INTERNAL;
import static cc.blynk.server.internal.CommonByteBufUtil.illegalCommand;
import static cc.blynk.server.internal.CommonByteBufUtil.makeASCIIStringMessage;
import static cc.blynk.server.internal.CommonByteBufUtil.ok;
import static cc.blynk.utils.StringUtils.BODY_SEPARATOR;

/**
 *
 * Simple handler that accepts info command from hardware.
 * At the moment only 1 param is used "h-beat".
 *
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 2/1/2015.
 *
 */
public final class BlynkInternalLogic {

    private static final Logger log = LogManager.getLogger(BlynkInternalLogic.class);

    private final OTAManager otaManager;
    private final int hardwareIdleTimeout;
    private static BlynkInternalLogic instance;

    private BlynkInternalLogic(Holder holder) {
        this(holder.otaManager, holder.limits.hardwareIdleTimeout);
    }

    //for tests only
    public BlynkInternalLogic(OTAManager otaManager, int hardwareIdleTimeout) {
        this.otaManager = otaManager;
        this.hardwareIdleTimeout = hardwareIdleTimeout;
    }

    public static BlynkInternalLogic getInstance(Holder holder) {
        if (instance == null) {
            instance = new BlynkInternalLogic(holder);
        }
        return instance;
    }

    public void messageReceived(ChannelHandlerContext ctx, HardwareStateHolder state, StringMessage message) {
        var messageParts = message.body.split(StringUtils.BODY_SEPARATOR_STRING);

        if (messageParts.length == 0 || messageParts[0].length() == 0) {
            ctx.writeAndFlush(illegalCommand(message.id), ctx.voidPromise());
            return;
        }

        var cmd = messageParts[0];

        switch (cmd.charAt(0)) {
            case 'v' : //ver
            case 'f' : //fw
            case 'h' : //h-beat
            case 'b' : //buff-in
            case 'd' : //dev
            case 'c' : //cpu
            case 't' : //tmpl
                parseHardwareInfo(ctx, messageParts, state, message.id);
                break;
            case 'r' : //rtc
                sendRTC(ctx, state, message.id);
                break;
            case 'a' :
            case 'o' :
                break;
        }

    }

    private void sendRTC(ChannelHandlerContext ctx, HardwareStateHolder state, int msgId) {
        var dashBoard = state.dash;
        var rtc = dashBoard.getWidgetByType(RTC.class);
        if (rtc != null && ctx.channel().isWritable()) {
            ctx.writeAndFlush(makeASCIIStringMessage(BLYNK_INTERNAL, msgId, "rtc" + BODY_SEPARATOR + rtc.getTime()),
                    ctx.voidPromise());
        }
    }

    private void parseHardwareInfo(ChannelHandlerContext ctx, String[] messageParts,
                                   HardwareStateHolder state, int msgId) {
        var hardwareInfo = new HardwareInfo(messageParts);
        var newHardwareInterval = hardwareInfo.heartbeatInterval;

        log.trace("Info command. heartbeat interval {}", newHardwareInterval);

        if (hardwareIdleTimeout != 0 && newHardwareInterval > 0) {
            var newReadTimeout = (int) Math.ceil(newHardwareInterval * 2.3D);
            log.debug("Changing read timeout interval to {}", newReadTimeout);
            ctx.pipeline().replace(IdleStateHandler.class,
                    "H_IdleStateHandler_Replaced", new IdleStateHandler(newReadTimeout, 0, 0));
        }

        var dashBoard = state.dash;
        var device = state.device;

        if (device != null) {
            otaManager.initiateHardwareUpdate(ctx, state.userKey, hardwareInfo, dashBoard, device);
            device.hardwareInfo = hardwareInfo;
            dashBoard.updatedAt = System.currentTimeMillis();
        }

        ctx.writeAndFlush(ok(msgId), ctx.voidPromise());
    }

}
