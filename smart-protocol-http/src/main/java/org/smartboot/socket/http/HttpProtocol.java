/*
 * Copyright (c) 2018, org.smartboot. All rights reserved.
 * project name: smart-socket
 * file name: HttpV2Protocol.java
 * Date: 2018-01-23
 * Author: sandao
 */

package org.smartboot.socket.http;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.smartboot.socket.Protocol;
import org.smartboot.socket.extension.decoder.DelimiterFrameDecoder;
import org.smartboot.socket.extension.decoder.FixedLengthFrameDecoder;
import org.smartboot.socket.extension.decoder.StreamFrameDecoder;
import org.smartboot.socket.transport.AioSession;

import java.nio.ByteBuffer;

/**
 * Http消息解析器,仅解析Header部分即可
 * Created by 三刀 on 2017/6/20.
 */
public class HttpProtocol implements Protocol<HttpRequest> {
    private static final Logger LOGGER = LogManager.getLogger(HttpProtocol.class);
    private static final byte[] LINE_END_BYTES = {'\r', '\n'};

    @Override
    public HttpRequest decode(ByteBuffer buffer, AioSession<HttpRequest> session, boolean eof) {

        HttpDecodeUnit decodeUnit = getHttpDecodeUnit(session);
        HttpRequest entity = decodeUnit.entity;

        boolean returnEntity = false;//是否返回HttpEntity
        boolean continueDecode = true;//是否继续读取数据
        while (buffer.hasRemaining() && continueDecode) {
            switch (decodeUnit.partEnum) {
                //解析请求行
                case REQUEST_LINE:
                    if (decodeUnit.headPartDecoder.decode(buffer)) {
                        decodeRequestLine(decodeUnit);
                    }
                    break;
                //解析消息头
                case HEAD_LINE:
                    if (decodeUnit.headPartDecoder.decode(buffer)) {
                        decodeHeadLine(decodeUnit);
                    }
                    if (decodeUnit.partEnum != HttpPartEnum.HEAD_END_LINE) {
                        break;
                    }
                    //识别如何处理Body部分
                case HEAD_END_LINE: {
                    decodeHeadEnd(decodeUnit);

                    if (decodeUnit.partEnum == HttpPartEnum.END) {
                        returnEntity = true;
                        continueDecode = false;
                    }
                    //文件流消息
                    else if (decodeUnit.bodyTypeEnum == BodyTypeEnum.STREAM) {
                        returnEntity = true;
                    }
                    break;
                }
                case BODY: {
                    decodeBody(decodeUnit, buffer);
                    break;
                }
                default: {
                    session.setAttachment(null);
                }
            }
        }
        if (decodeUnit.partEnum == HttpPartEnum.END) {
            session.setAttachment(null);
        }
        return returnEntity ? entity : null;
    }

    /**
     * 获得解码对象模型
     *
     * @param session
     * @return
     */
    private HttpDecodeUnit getHttpDecodeUnit(AioSession<HttpRequest> session) {
        HttpDecodeUnit decodeUnit = null;
        if (session.getAttachment() == null) {
            decodeUnit = new HttpDecodeUnit();
            decodeUnit.entity = new HttpRequest(session);
            decodeUnit.partEnum = HttpPartEnum.REQUEST_LINE;
            decodeUnit.headPartDecoder = new DelimiterFrameDecoder(LINE_END_BYTES, 256);
            session.setAttachment(decodeUnit);
        } else {
            decodeUnit = (HttpDecodeUnit) session.getAttachment();
        }
        return decodeUnit;
    }

    @Override
    public ByteBuffer encode(HttpRequest httpRequest, AioSession<HttpRequest> session) {
        return null;
    }

    /**
     * 解析请求行
     *
     * @param unit
     */
    private void decodeRequestLine(HttpDecodeUnit unit) {
        ByteBuffer requestLineBuffer = unit.headPartDecoder.getBuffer();
        String[] requestLineDatas = StringUtils.split(new String(requestLineBuffer.array(), 0, requestLineBuffer.remaining()), " ");

        unit.entity.setMethod(requestLineDatas[0]);
        unit.entity.setUrl(requestLineDatas[1]);
        unit.entity.setProtocol(requestLineDatas[2]);

        //识别一下一个解码阶段
        unit.headPartDecoder.reset();
        unit.partEnum = HttpPartEnum.HEAD_LINE;
    }

    /**
     * 解析消息头
     *
     * @param unit
     */
    private void decodeHeadLine(HttpDecodeUnit unit) {
        ByteBuffer headLineBuffer = unit.headPartDecoder.getBuffer();

        //消息头已结束
        if (headLineBuffer.remaining() == LINE_END_BYTES.length) {
            unit.partEnum = HttpPartEnum.HEAD_END_LINE;
            unit.headPartDecoder.reset();
            return;
        }
        String[] headLineDatas = StringUtils.split(new String(headLineBuffer.array(), 0, headLineBuffer.remaining()), ":");

        unit.entity.setHeader(headLineDatas[0], headLineDatas[1]);

        //识别一下一个解码阶段
        unit.headPartDecoder.reset();
        unit.partEnum = HttpPartEnum.HEAD_LINE;
    }

    private void decodeHeadEnd(HttpDecodeUnit unit) {

        if (!StringUtils.equals("POST", unit.entity.getMethod())) {
            unit.partEnum = HttpPartEnum.END;
            return;
        }
        unit.partEnum = HttpPartEnum.BODY;
        //识别Body解码器
        String contentType = unit.entity.getHeader(HttpRequest.CONTENT_TYPE);
        int contentLength = unit.entity.getContentLength();
        if (StringUtils.startsWith(contentType, "multipart/form-data")) {
            unit.bodyTypeEnum = BodyTypeEnum.STREAM;
            unit.streamBodyDecoder = new StreamFrameDecoder(contentLength);
            unit.entity.setInputStream(unit.streamBodyDecoder.getInputStream());
        } else {
            unit.bodyTypeEnum = BodyTypeEnum.FORM;
            unit.formBodyDecoder = new FixedLengthFrameDecoder(contentLength);
            unit.entity.setInputStream(new EmptyInputStream());
        }
    }

    private void decodeBody(HttpDecodeUnit unit, ByteBuffer buffer) {
        switch (unit.bodyTypeEnum) {
            case FORM:
                if (unit.formBodyDecoder.decode(buffer)) {
                    decodeBodyForm(unit);
                    unit.partEnum = HttpPartEnum.END;
                }
                break;
            case STREAM:
                if (unit.streamBodyDecoder.decode(buffer)) {
                    unit.partEnum = HttpPartEnum.END;
                }
                break;
            default:
                throw new UnsupportedOperationException();
        }
    }


    private void decodeBodyForm(HttpDecodeUnit unit) {
        ByteBuffer buffer = unit.formBodyDecoder.getBuffer();
        String[] paramArray = StringUtils.split(new String(buffer.array(), buffer.position(), buffer.remaining()), "&");
        for (int i = 0; i < paramArray.length; i++) {
            unit.entity.setParam(StringUtils.substringBefore(paramArray[i], "=").trim(), StringUtils.substringAfter(paramArray[i], "=").trim());
        }
    }

    class HttpDecodeUnit {


        HttpRequest entity;
        /**
         * 当前解码阶段
         */
        HttpPartEnum partEnum;

        BodyTypeEnum bodyTypeEnum;
        /**
         * 结束标解码器
         */
        DelimiterFrameDecoder headPartDecoder;

        FixedLengthFrameDecoder formBodyDecoder;

        StreamFrameDecoder streamBodyDecoder;
    }
}
