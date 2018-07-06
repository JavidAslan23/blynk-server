package cc.blynk.server.hardware.handlers.hardware.logic;

import cc.blynk.server.Holder;
import cc.blynk.server.core.dao.SessionDao;
import cc.blynk.server.core.model.enums.PinType;
import cc.blynk.server.core.model.enums.WidgetProperty;
import cc.blynk.server.core.model.widgets.Widget;
import cc.blynk.server.core.protocol.model.messages.StringMessage;
import cc.blynk.server.core.session.HardwareStateHolder;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static cc.blynk.server.core.protocol.enums.Command.SET_WIDGET_PROPERTY;
import static cc.blynk.server.internal.CommonByteBufUtil.illegalCommandBody;
import static cc.blynk.server.internal.CommonByteBufUtil.ok;
import static cc.blynk.utils.StringUtils.split3;

/**
 * Handler that allows to change widget properties from hardware side.
 *
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 2/1/2015.
 *
 */
public final class SetWidgetPropertyLogic {

    private static final Logger log = LogManager.getLogger(SetWidgetPropertyLogic.class);

    private final SessionDao sessionDao;

    private static SetWidgetPropertyLogic instance;

    private SetWidgetPropertyLogic(Holder holder) {
        this.sessionDao = holder.sessionDao;
    }

    public static SetWidgetPropertyLogic getInstance(Holder holder) {
        if (instance == null) {
            instance = new SetWidgetPropertyLogic(holder);
        }
        return instance;
    }

    public void messageReceived(ChannelHandlerContext ctx, HardwareStateHolder state, StringMessage message) {
        var bodyParts = split3(message.body);

        if (bodyParts.length != 3) {
            log.debug("SetWidgetProperty command body has wrong format. {}", message.body);
            ctx.writeAndFlush(illegalCommandBody(message.id), ctx.voidPromise());
            return;
        }

        var property = bodyParts[1];
        var propertyValue = bodyParts[2];

        if (property.length() == 0 || propertyValue.length() == 0) {
            log.debug("SetWidgetProperty command body has wrong format. {}", message.body);
            ctx.writeAndFlush(illegalCommandBody(message.id), ctx.voidPromise());
            return;
        }

        var dash = state.dash;

        if (!dash.isActive) {
            return;
        }

        var widgetProperty = WidgetProperty.getProperty(property);

        if (widgetProperty == null) {
            log.debug("Unsupported set property {}.", property);
            ctx.writeAndFlush(illegalCommandBody(message.id), ctx.voidPromise());
            return;
        }

        var deviceId = state.device.id;
        var pin = Byte.parseByte(bodyParts[0]);

        Widget widget = null;
        for (Widget dashWidget : dash.widgets) {
            if (dashWidget.isSame(deviceId, pin, PinType.VIRTUAL)) {
                try {
                    dashWidget.setProperty(widgetProperty, propertyValue);
                    dash.updatedAt = System.currentTimeMillis();
                } catch (Exception e) {
                    log.debug("Error setting widget property. Reason : {}", e.getMessage());
                    ctx.writeAndFlush(illegalCommandBody(message.id), ctx.voidPromise());
                    return;
                }
                widget = dashWidget;
            }
        }

        //this is possible case for device selector
        if (widget == null) {
            dash.putPinPropertyStorageValue(deviceId, PinType.VIRTUAL, pin, widgetProperty, propertyValue);
        }

        var session = sessionDao.userSession.get(state.userKey);
        session.sendToApps(SET_WIDGET_PROPERTY, message.id, dash.id, deviceId, message.body);
        ctx.writeAndFlush(ok(message.id), ctx.voidPromise());
    }

}
