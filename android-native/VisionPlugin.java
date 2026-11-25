package com.toptopai.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device image understanding. No API key and no network request: the models are
 * bundled with the APK through ML Kit. This is the practical Android equivalent of
 * the "simple computer vision" idea behind ImageAI, without shipping Python inside
 * a WebView.
 */
@CapacitorPlugin(name = "Vision")
public class VisionPlugin extends Plugin {

    private static final ExecutorService WORK = Executors.newFixedThreadPool(2);
    private static final int MAX_SIDE = 1600;

    public void analyze(PluginCall call) {
        String data = call.getString("image", "");
        if (data == null || data.trim().isEmpty()) {
            call.reject("image_required", "No image data was supplied");
            return;
        }

        WORK.execute(() -> {
            ImageLabeler labeler = null;
            TextRecognizer textRecognizer = null;
            BarcodeScanner barcodeScanner = null;
            try {
                Bitmap bitmap = decode(data);
                InputImage image = InputImage.fromBitmap(bitmap, 0);

                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("width", bitmap.getWidth());
                out.put("height", bitmap.getHeight());

                labeler = ImageLabeling.getClient(
                        new ImageLabelerOptions.Builder().setConfidenceThreshold(0.45f).build());
                List<ImageLabel> labels = Tasks.await(labeler.process(image));
                Collections.sort(labels, new Comparator<ImageLabel>() {
                    @Override
                    public int compare(ImageLabel a, ImageLabel b) {
                        return Float.compare(b.getConfidence(), a.getConfidence());
                    }
                });
                JSONArray labelsJson = new JSONArray();
                for (ImageLabel label : labels) {
                    if (labelsJson.length() >= 12) break;
                    JSONObject item = new JSONObject();
                    item.put("text", label.getText());
                    item.put("confidence", Math.round(label.getConfidence() * 100) / 100.0);
                    labelsJson.put(item);
                }
                out.put("labels", labelsJson);

                textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                Text text = Tasks.await(textRecognizer.process(image));
                out.put("text", trim(text.getText(), 4000));

                BarcodeScannerOptions barcodeOptions = new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                                Barcode.FORMAT_QR_CODE,
                                Barcode.FORMAT_EAN_13,
                                Barcode.FORMAT_EAN_8,
                                Barcode.FORMAT_UPC_A,
                                Barcode.FORMAT_UPC_E,
                                Barcode.FORMAT_CODE_128)
                        .build();
                barcodeScanner = BarcodeScanning.getClient(barcodeOptions);
                List<Barcode> codes = Tasks.await(barcodeScanner.process(image));
                JSONArray codesJson = new JSONArray();
                for (Barcode code : codes) {
                    JSONObject item = new JSONObject();
                    String raw = code.getRawValue() == null ? "" : code.getRawValue();
                    String url = code.getUrl() == null ? "" : code.getUrl().getUrl();
                    item.put("raw", raw);
                    item.put("url", url);
                    item.put("format", code.getFormat());
                    codesJson.put(item);
                    if (codesJson.length() >= 10) break;
                }
                out.put("barcodes", codesJson);

                out.put("colors", dominantColors(bitmap));
                call.resolve(JSObject.fromJSONObject(out));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "Image analysis failed" : e.getMessage();
                call.reject("vision_failed", msg);
            } finally {
                if (labeler != null) labeler.close();
                if (textRecognizer != null) textRecognizer.close();
                if (barcodeScanner != null) barcodeScanner.close();
            }
        });
    }

    private static Bitmap decode(String data) throws Exception {
        String raw = data.trim();
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) raw = raw.substring(comma + 1);
        byte[] bytes = Base64.decode(raw, Base64.DEFAULT);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int scale = (int) Math.ceil(longest / (double) MAX_SIDE);
        if (scale < 1) scale = 1;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = scale;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new Exception("Could not decode image");
        return bitmap;
    }

    private static JSONArray dominantColors(Bitmap bitmap) throws Exception {
        final int n = 32;
        Bitmap small = Bitmap.createScaledBitmap(bitmap, n, n, true);
        int[] pixels = new int[n * n];
        small.getPixels(pixels, 0, n, 0, 0, n, n);

        Map<Integer, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (int c : pixels) {
            if (((c >> 24) & 0xFF) < 128) continue;
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int key = ((r >> 5) << 10) | ((g >> 5) << 5) | (b >> 5);
            Integer old = counts.get(key);
            counts.put(key, old == null ? 1 : old + 1);
            total++;
        }
        if (total == 0) total = pixels.length;

        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(counts.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<Integer, Integer>>() {
            @Override
            public int compare(Map.Entry<Integer, Integer> a, Map.Entry<Integer, Integer> b) {
                return Integer.compare(b.getValue(), a.getValue());
            }
        });

        JSONArray out = new JSONArray();
        for (Map.Entry<Integer, Integer> entry : sorted) {
            if (out.length() >= 5) break;
            int key = entry.getKey();
            int r = ((key >> 10) & 31) * 8 + 4;
            int g = ((key >> 5) & 31) * 8 + 4;
            int b = (key & 31) * 8 + 4;
            JSONObject color = new JSONObject();
            color.put("hex", String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b));
            color.put("share", Math.round(entry.getValue() * 100.0 / total));
            out.put(color);
        }
        return out;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String v = s.trim();
        return v.length() <= max ? v : v.substring(0, max) + "\n…(اقتُطع النص)";
    }
}
