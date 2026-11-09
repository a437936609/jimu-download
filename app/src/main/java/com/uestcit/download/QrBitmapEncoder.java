package com.uestcit.download;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * 仅编码二维码为 Bitmap（车机无摄像头，不做扫码）。
 */
final class QrBitmapEncoder {

    private QrBitmapEncoder() {
    }

    /**
     * @param content 完整 URL
     * @param sizePx  正方形边长（建议 400+，车机远距可读）
     */
    static Bitmap encode(String content, int sizePx) throws Exception {
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("二维码内容为空");
        }
        int size = Math.max(200, sizePx);
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, size, size, hints);
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int offset = y * w;
            for (int x = 0; x < w; x++) {
                pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }
}
