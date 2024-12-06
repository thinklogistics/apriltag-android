package edu.umich.eecs.april.apriltag;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Log;
import android.view.TextureView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class DetectionThread extends Thread {

    private static final String TAG = "DetectionThread";
    private TextureView mTextureView;

    private final TextView mFpsTextView;
    private long mLastFPSRender = System.currentTimeMillis();
    private Camera.Size mCameraSize;
    private static final int MAX_FRAME_QUEUE_SIZE = 1;

    private BlockingQueue<byte[]> mCameraFrameQueue = new LinkedBlockingQueue<>();
    private long mLastEnqueueFrameTime;
    private int mFrameCount = 0;
    private long mLastDetectLatency = 0;



    public DetectionThread(TextureView textureView, TextView fpsTextView) {
        mTextureView = textureView;
        mFpsTextView = fpsTextView;
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                // Do nothing
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                // Do nothing
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // Do nothing
            }
        });
    }

    public void destroy() {
        mCameraFrameQueue.clear();
        mCameraFrameQueue = null;
    }

    public void enqueueCameraFrame(byte[] data, Camera.Size cameraSize) throws InterruptedException {
        if (mCameraSize == null || mCameraSize.width != cameraSize.width || mCameraSize.height != cameraSize.height) {
            mCameraFrameQueue.clear();
            mCameraSize = cameraSize;
            Log.w(TAG, "Camera size changed during preview");
        }

        if (mCameraFrameQueue == null) {
            Log.w(TAG, "Camera frame queue is null, skipping frame");
            return;
        }

        if (mCameraFrameQueue.size() == MAX_FRAME_QUEUE_SIZE) {
            mCameraFrameQueue.clear();
            Log.w(TAG, "Camera frame queue is full, clearing buffer");
        }

        mCameraFrameQueue.put(data);
        mLastEnqueueFrameTime = System.currentTimeMillis();

        Log.i(TAG, "Buffer length: " + mCameraFrameQueue.size());
    }

    private void updateFps() {
        long now = System.currentTimeMillis();
        long diff = now - mLastFPSRender;
        mFrameCount++;
        if (diff >= 1000) {
            final double fps = 1000.0 / diff * mFrameCount;
            mFpsTextView.post(new Runnable() {
                @Override
                public void run() {
                    mFpsTextView.setText(String.format("%.2f fps Detect+Render\n%d ms Detect+Render Latency", fps, mLastDetectLatency));
                }
            });
            mLastFPSRender = now;
            mFrameCount = 0;
        }
    }

    private ArrayList<ApriltagDetection> processCameraFrame(byte[] data, Camera.Size cameraSize)  {
        try {
            return ApriltagNative.apriltag_detect_yuv(data, cameraSize.width, cameraSize.height);
        } catch (Exception e) {
            Log.e(TAG, "Unhandled exception when detecting tags: " + e);
            return new ArrayList<>();
        }
    }

    private void renderDetection(ApriltagDetection detection, Canvas canvas) {
        Paint fillPaint = new Paint();
        fillPaint.setColor(Color.GREEN);
        fillPaint.setAlpha(128);
        fillPaint.setStyle(Paint.Style.FILL);

        Paint borderPaint = new Paint();
        final int[] borderColors = new int[]{Color.GREEN, Color.WHITE, Color.WHITE, Color.RED};
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(10);

        float scaleDetectionX = (float)(canvas.getHeight()) / mCameraSize.width; // Converts detection x to render y
        float scaleDetectionY = (float)(canvas.getWidth()) / mCameraSize.height; // Converts detection y to render x (still needs offset)

        double[] points = detection.p;
        if (points == null || points.length != 8) {
            Log.w(TAG, "invalid detection coordinates");
            return;
        }

        // Convert detection points to canvas points
        float[] xPointsCanvas = new float[4];
        float[] yPointsCanvas = new float[4];
        for (int i = 0; i < 4; i++) {
            xPointsCanvas[i] = (float) (canvas.getWidth() - points[i * 2 + 1] * scaleDetectionY);
            yPointsCanvas[i] = (float) (points[i * 2] * scaleDetectionX);
        }

        // Render filled outline of detections
        Path fillPath = new Path();
        for (int i = 0; i < 4; i++) {
            if (i == 0) {
                fillPath.moveTo(xPointsCanvas[i], yPointsCanvas[i]);
            } else {
                fillPath.lineTo(xPointsCanvas[i], yPointsCanvas[i]);
            }
        }
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);

        // Render stroke outline of detections
        int colorIndex = 0;
        for (int i = 0; i < 4; i++) {
            Path borderPath = new Path();
            borderPaint.setColor(borderColors[colorIndex++ % borderColors.length]);

            borderPath.moveTo(xPointsCanvas[i], yPointsCanvas[i]);
            borderPath.lineTo(xPointsCanvas[(i + 1) % 4], yPointsCanvas[(i + 1) % 4]);
            canvas.drawPath(borderPath, borderPaint);
        }

        // Render tag ID in the center of the detection box
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(100);
        String tagId = String.valueOf(detection.id);
        float textWidth = textPaint.measureText(tagId);
        float textHeight = textPaint.getFontMetrics().descent - textPaint.getFontMetrics().ascent;
        float textX = (float) (canvas.getWidth() - detection.c[1] * scaleDetectionY - textWidth / 2);
        float textY = (float) (detection.c[0] * scaleDetectionX + textHeight / 2 - textPaint.getFontMetrics().descent);
        canvas.drawText(tagId, textX, textY, textPaint);
    }

    private void renderDetections(ArrayList<ApriltagDetection> detections) {
        Canvas canvas = mTextureView.lockCanvas();
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);

            if (!detections.isEmpty()) {
                float scaleDetectionY = (float)(canvas.getWidth()) / mCameraSize.height; // Converts detection y to render x (still needs offset)

                //array that contains newly generated apriltags (adjacent tags)
                ArrayList<ApriltagDetection> adjacentApriltags = new ArrayList<>();

                //performed apriltags,
                ArrayList<ApriltagDetection> performedApriltags = new ArrayList<>();

                for (ApriltagDetection detection: detections) {
                    if (performedApriltags.contains(detection)) {
                        continue;
                    }

                    //find adjacent apriltags
                    for (ApriltagDetection adjacentDetection: detections) {
                        if (adjacentDetection.equals(detection)) {
                            continue;
                        }

                        //distance between 2 april tags (can be from center id)
                        double distance = Math.sqrt(Math.pow(detection.c[0] - adjacentDetection.c[0], 2) + Math.pow(detection.c[1] - adjacentDetection.c[1], 2));
                        double width = Math.sqrt(Math.pow(detection.p[0] - detection.p[2], 2) + Math.pow(detection.p[1] - detection.p[3], 2));

                        if (distance < width * 3 / 2) {
                            //these are adjacent tags, both detection and adjacentDetection tags will be added to performed tags array.
                            performedApriltags.add(adjacentDetection);

                            //generate new apriltag and draw
                            ApriltagDetection newDetection = new ApriltagDetection();

                            //define major and minor apriltags based on x coordinates and y-axis rotation angle
                            float xPointsCanvasDetection = (float) (canvas.getWidth() - detection.p[1] * scaleDetectionY);
                            float xPointsCanvasAdjacentDetection = (float) (canvas.getWidth() - adjacentDetection.p[1] * scaleDetectionY);

                            ApriltagDetection major;
                            //we have to give some thresold in angles, when camera is rotated in other angle range, these tags will be marked as error tags,
                            //and will not be drawn, can't detect major and minor without thresold.
                            if (detection.getRoll() > 200 && detection.getRoll() < 340) {
                                major = (xPointsCanvasDetection < xPointsCanvasAdjacentDetection) ? detection : adjacentDetection;
                            }else if (detection.getRoll() > 20 && detection.getRoll() < 160) {
                                major = (xPointsCanvasDetection < xPointsCanvasAdjacentDetection) ? adjacentDetection : detection;
                            }else {
                                continue;
                            }

                            ApriltagDetection minor = major == detection ? adjacentDetection : detection;

                            //major and minor tag is detected,
                            //we have to generate new tag info to draw, generate new combined id, and coordinates.
                            newDetection.id = major.id * 587 + minor.id;
                            newDetection.c[0] = (adjacentDetection.c[0] + detection.c[0]) / 2;
                            newDetection.c[1] = (adjacentDetection.c[1] + detection.c[1]) / 2;

                            newDetection.p[0] = major.p[0];
                            newDetection.p[1] = major.p[1];
                            newDetection.p[2] = minor.p[2];
                            newDetection.p[3] = minor.p[3];
                            newDetection.p[4] = minor.p[4];
                            newDetection.p[5] = minor.p[5];
                            newDetection.p[6] = major.p[6];
                            newDetection.p[7] = major.p[7];
                            adjacentApriltags.add(newDetection);
                        }
                    }

                    performedApriltags.add(detection);
                }
                for (ApriltagDetection detection : adjacentApriltags) {
                    //draw adjacent tags.
                    renderDetection(detection, canvas);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error rendering detections: " + e.getMessage());
        } finally {
            if (canvas != null) {
                mTextureView.unlockCanvasAndPost(canvas);
            }
        }
    }

    public void initialize() {
        Log.i(TAG, "Detection thread initialize");
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            updateFps();

            if (mCameraFrameQueue == null) {
                continue;
            }

            byte[] data;
            try {
                data = mCameraFrameQueue.take();
            } catch (InterruptedException e) {
                Log.i(TAG, "Interrupted while waiting for camera frame: " + e.getMessage());
                break;
            }

            ArrayList<ApriltagDetection> detections = processCameraFrame(data, mCameraSize);

            renderDetections(detections);

            mLastDetectLatency = (System.currentTimeMillis() - mLastEnqueueFrameTime);
        }
    }
}