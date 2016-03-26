package com.robotca.ControlApp.Layers;

import android.graphics.PointF;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import com.google.common.base.Preconditions;
import com.robotca.ControlApp.ControlApp;
import com.robotca.ControlApp.Core.RobotController;
import com.robotca.ControlApp.Core.Utils;

import org.ros.android.view.visualization.Color;
import org.ros.android.view.visualization.Vertices;
import org.ros.android.view.visualization.VisualizationView;
import org.ros.android.view.visualization.layer.SubscriberLayer;
import org.ros.android.view.visualization.layer.TfLayer;
import org.ros.android.view.visualization.shape.PixelSpacePoseShape;
import org.ros.android.view.visualization.shape.Shape;
import org.ros.message.MessageListener;
import org.ros.namespace.GraphName;
import org.ros.node.ConnectedNode;
import org.ros.node.topic.Subscriber;
import org.ros.rosjava_geometry.Vector3;

import java.nio.FloatBuffer;

import javax.microedition.khronos.opengles.GL10;

import sensor_msgs.LaserScan;

/**
 * Improved version of the ros laser scan layer.
 * Instead of using a preset stride to limit the number of points drawn, points are drawn when their
 * distance from the last drawn point exceeds some value.
 * <p/>
 * Created by Nathaniel Stone on 2/12/16.
 * <p/>
 * Copyright (C) 2011 Google Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
public class LaserScanLayer extends SubscriberLayer<LaserScan> implements TfLayer {

    // Base color of the laser scan
    private static final Color LASER_SCAN_COLOR = Color.fromHexAndAlpha("377dfa", 0.1f);

    // Default point size of laser scan points
    private static final float LASER_SCAN_POINT_SIZE = 10.f;

    // Only adds laser scan points if they are at least this much further from the previous point
    private static final float MIN_DISTANCE_SQUARED = 20.0e-2f; // meters

    // Used for calculating range color
    private static final float MAX_DISTANCE = 10.0f; // meters

    private VisualizationView view;

    // Lock for synchronizing drawing
    private final Object mutex;
    private final ControlApp controlApp;
    private final ScaleGestureDetector scaleGestureDetector;
    private final GestureDetector gestureDetector;

    // Controls the density of scan po;ints
    private float laserScanDetail;

//    // For tracking robot pose
//    private Vector3 robotPosition;
//    private double robotHeading;

    // GraphName for LaserScan
    private GraphName frame;

    // Buffer of scan points for drawing
    private FloatBuffer vertexFrontBuffer;

    // Buffer of scan points for updating
    private FloatBuffer vertexBackBuffer;

    // Used for panning the view
    private boolean isMoving;
//    private boolean hasMoved;
    private float xStart, yStart;
    private float xShift, yShift;
    private float offX, offY;
    private float zoomLevel = 1;

    // Shape to draw to show the robot's position
    private Shape shape;

    private long lastTime;
    private static final long DELAY = 10L;

    @SuppressWarnings("unused")
    private static final String TAG = "LaserScanLayer";

    /**
     * Creates a LaserScanLayer.
     *
     * @param topicName Topic name for laser scanner
     * @param detail    Detail of drawn points
     */
    public LaserScanLayer(String topicName, float detail, ControlApp app) {
        this(GraphName.of(topicName), detail, app);
    }

    /**
     * Creates a LaserScanLayer.
     *
     * @param topicName Topic name for laser scanner
     * @param detail    Detail of drawn points
     */
    public LaserScanLayer(GraphName topicName, float detail, ControlApp app) {
        super(topicName, sensor_msgs.LaserScan._TYPE);

        this.mutex = new Object();

        this.laserScanDetail = Math.max(detail, 1);
        this.controlApp = app;

        this.scaleGestureDetector = new ScaleGestureDetector(controlApp,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {

                // Don't let the object get too small or too large.
                zoomLevel = Math.max(0.1f, Math.min(detector.getScaleFactor(), 5.0f));

//                Log.d(TAG, String.format("Zoom: %f", zoomLevel));

                if (view != null) {
//                    view.getCamera().zoom(detector.getFocusX(), detector.getFocusY(), zoomLevel);

                    float s = 1f / (float) view.getCamera().getZoom();
                    int w = view.getCamera().getViewport().getWidth() / 2;
                    int h = view.getCamera().getViewport().getHeight() / 2;

                    xShift = xStart - (detector.getFocusY() - h) * s + offX;
                    yShift = yStart + (detector.getFocusX() - w) * s + offY;

                    view.getCamera().zoom(w, h, zoomLevel);
                }

                return true;
            }

            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {

                xStart = (float) ((detector.getFocusY() - view.getCamera().getViewport().getHeight() / 2)
                        / view.getCamera().getZoom());
                yStart = -(float) ((detector.getFocusX() - view.getCamera().getViewport().getWidth() / 2)
                        / view.getCamera().getZoom());

                offX = xShift;
                offY = yShift;

                return true;
            }
        });

        this.gestureDetector = new GestureDetector(controlApp, new GestureDetector.SimpleOnGestureListener()
        {
            @Override
            public boolean onDoubleTap(MotionEvent e)
            {
                if (view == null)
                    return false;

                try {
                    double s = 1.0 / view.getCamera().getZoom();

                    double height = controlApp.getResources().getDisplayMetrics().heightPixels;
                    double width = controlApp.getResources().getDisplayMetrics().widthPixels;

                    double x = e.getX() + (width - view.getWidth()) - width / 2.0;
                    double y = e.getY() + (height - view.getHeight()) / 2.0 - height / 2.0;

                    x *= s;
                    y *= s;

                    //noinspection SuspiciousNameCombination
                    x -= yShift;
                    //noinspection SuspiciousNameCombination
                    y += xShift;

                    controlApp.addWaypointWithCheck(screenToWorld(y, x));
                } catch (Exception ex) {
                    // Ignore
                }
                return false;
            }
        });

        xShift = yShift = 0.0f;
    }

    /**
     * Recenters the LaserScanLayer.
     */
    public void recenter() {
        isMoving = false;
        xShift = 0.0f;
        yShift = 0.0f;
    }

    /**
     * Callback for touch events to this Layer.
     *
     * @param view  The touched View
     * @param event The touch MotionEvent
     * @return True indicating the even was handled successfully
     */
    public boolean onTouchEvent(VisualizationView view, MotionEvent event) {
        final float s = 1.0f / (float) view.getCamera().getZoom();

        scaleGestureDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        boolean r = true;

        switch (event.getAction() & MotionEvent.ACTION_MASK) {

            case MotionEvent.ACTION_POINTER_DOWN:
                isMoving = false;
                Log.d(TAG, "Pointer Down");
                r = false;
                break;

            case MotionEvent.ACTION_POINTER_UP:
                Log.d(TAG, "Pointer Up");
                break;

            case MotionEvent.ACTION_DOWN:
                if (!isMoving) {
                    isMoving = true;
//                    hasMoved = false;
                    xStart = event.getY() * s;
                    yStart = -event.getX() * s;

                    offX = xShift;
                    offY = yShift;
                }
                break;
            case MotionEvent.ACTION_UP:

                if (isMoving) {
                    isMoving = false;
                }

//                hasMoved = false;
                break;
            case MotionEvent.ACTION_MOVE:

                if (isMoving && !scaleGestureDetector.isInProgress()) {
                    xShift = xStart - event.getY() * s + offX;
                    yShift = yStart + event.getX() * s + offY;

//                    if (Math.abs(xStart - event.getY() * s) > 0.2f || Math.abs(yStart + event.getX() * s) > 0.2f) {
//                        hasMoved = true;
//                    }
                }
                break;
        }

        return r;
    }

    /**
     * Draws the LaserScan.
     *
     * @param view The VisualizationView this LaserScanLayer is attached to.
     * @param gl   The GL10 instance for drawing
     */
    @Override
    public void draw(VisualizationView view, GL10 gl) {

        this.view = view;

        if (vertexFrontBuffer != null) {

            final float Z = (float) view.getCamera().getZoom() / 100.0f;
            final float XS = xShift;
            final float YS = yShift;

            synchronized (mutex) {
                // Draw the shape
                gl.glTranslatef(XS, YS, 0.0f);

                // Draw start position
                drawPoint(gl, 0.0, 0.0, 32.0f, 0xFFCCCCDD, null);

                // Draw the robot
                gl.glScalef(Z, Z, 1.0f);
                shape.draw(view, gl);
                gl.glScalef(1f / Z, 1f / Z, 1.0f);

                // Draw the scan area
                drawPoints(gl, vertexFrontBuffer, 0.0f, true);

                // Drop the first point which is required for the triangle fan but is
                // not a range reading.
                FloatBuffer pointVertices = vertexFrontBuffer.duplicate();
                pointVertices.position(3 + 4);

                // Draw the scan points
                drawPoints(gl, pointVertices, LASER_SCAN_POINT_SIZE, false);

                // Draw waypoints
                drawWayPoints(gl);

//                // Imminent collision detection
//                double d = Math.max(0.5, Math.pow(HUDFragment.getSpeed() * 1.25, 2.0));
//                double r = -HUDFragment.getTurnRate();
//
//                d /= Math.cos(r);
//                r /= 2.0;
//
//                if (HUDFragment.getSpeed() < -0.01) {
//                    d = -d;
//                    r = -r;
//                }
//
//                // Just have to check if one of the points intersects the point fan
//                boolean collision = false;
//
//                for (int i = -2; i <= 2; ++i) {
//                    float x = (float) (Math.cos(r + i * Math.PI / (18.0 * d)) * d);
//                    float y = (float) (Math.sin(r + i * Math.PI / (18.0 * d)) * d);
//                    Utils.drawPoint(gl, x, y, 3.0f, 0xFFFF0000);
//
//                    // collision |= ...
//                }
//
//                if (collision) {
//                    controlApp.collisionWarning();
//                }

                gl.glTranslatef(-XS, -YS, 0.0f);
//                gl.glScalef(zoomLevel, zoomLevel, 1.0f);
            }
        }
    }

    /**
     * Draws the contents of the specified buffer.
     *
     * @param gl       GL10 object for drawing
     * @param vertices FloatBuffer of vertices to draw
     * @param size     Size of draw points
     * @param fan      If true, draws the buffer as a triangle fan, otherwise draws it as a point cloud
     */
    private static void drawPoints(GL10 gl, FloatBuffer vertices, float size, boolean fan) {
        vertices.mark();

        if (!fan)
            gl.glPointSize(size);

        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glEnableClientState(GL10.GL_COLOR_ARRAY);

        gl.glVertexPointer(3, GL10.GL_FLOAT, (3 + 4) * 4, vertices);

        FloatBuffer colors = vertices.duplicate();
        colors.position(fan ? 3 : 10);
        gl.glColorPointer(4, GL10.GL_FLOAT, (3 + 4) * 4, colors);

        gl.glDrawArrays(fan ? GL10.GL_TRIANGLE_FAN : GL10.GL_POINTS, 0, countVertices(vertices, 3 + 4));

        if (!fan) {
            gl.glDrawArrays(GL10.GL_POINTS, 0, countVertices(vertices, 3 + 4));
        }

        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glDisableClientState(GL10.GL_COLOR_ARRAY);

        vertices.reset();
    }

    /*
     * Helper function to calculate the number of vertices in a FloatBuffer.
     */
    private static int countVertices(FloatBuffer vertices, int size) {
        Preconditions.checkArgument(vertices.remaining() % size == 0, "Number of vertices: " + vertices.remaining());
        return vertices.remaining() / size;
    }

    /**
     * Initializes the LaserScanLayer.
     *
     * @param view          Parent VisualizationView
     * @param connectedNode Node this layer is connected to
     */
    @Override
    public void onStart(VisualizationView view, ConnectedNode connectedNode) {
        super.onStart(view, connectedNode);
        Subscriber<LaserScan> subscriber = getSubscriber();
        subscriber.addMessageListener(new MessageListener<LaserScan>() {
            @Override
            public void onNewMessage(LaserScan laserScan) {

                if (System.currentTimeMillis() - lastTime > DELAY) {
                    lastTime = System.currentTimeMillis();
                    frame = GraphName.of(laserScan.getHeader().getFrameId());
                    updateVertexBuffer(laserScan);
                }
            }
        });

        this.shape = new PixelSpacePoseShape();
    }

    /*
     * Updates the contents of the vertexBackBuffer to the result of the specified LaserScan.
     */
    private void updateVertexBuffer(LaserScan laserScan) {

        if (view == null)
            return;

        float[] ranges = laserScan.getRanges();
        int size = ((ranges.length) + 2) * (3 + 4);//((ranges.length / stride) + 2) * 3;

        if (vertexBackBuffer == null || vertexBackBuffer.capacity() < size) {
            vertexBackBuffer = Vertices.allocateBuffer(size);
        }

        vertexBackBuffer.clear();

        // We start with the origin of the triangle fan.
        vertexBackBuffer.put(0.0f);
        vertexBackBuffer.put(0.0f);
        vertexBackBuffer.put(0);

        // Color
        vertexBackBuffer.put(LASER_SCAN_COLOR.getRed());
        vertexBackBuffer.put(LASER_SCAN_COLOR.getGreen());
        vertexBackBuffer.put(LASER_SCAN_COLOR.getBlue());
        vertexBackBuffer.put(0.1f);

        float angle = laserScan.getAngleMin();
        float angleIncrement = laserScan.getAngleIncrement();

        float x, y, xp, yp = xp = 0.0f;
        int num = 0;
        float p;

//        boolean onView;
        final float W = (float) (view.getWidth() / view.getCamera().getZoom()) + Math.abs(xShift);
        final float H = (float) (view.getHeight() / view.getCamera().getZoom()) + Math.abs(yShift);
        final float MAX_RANGE = (float) Math.sqrt(W * W + H * H);

        boolean draw;
        float scale;

        // Calculate the coordinates of the laser range values.
        for (int i = 0; i < ranges.length; ++i) {
            // Makes the preview look nicer by eliminating round off errors on the last angle
            if (i == ranges.length - 1)
                angle = laserScan.getAngleMax();

            if (ranges[i] > MAX_RANGE)
                ranges[i] = MAX_RANGE;

            // x, y, z
            x = (float) (ranges[i] * Math.cos(angle));
            y = (float) (ranges[i] * Math.sin(angle));

            p = ranges[i];

            if (p > MAX_DISTANCE) {
                p = 1.0f;
            } else {
                p /= MAX_DISTANCE;
            }

//            pt = screenToWorld(x, y);
//            Log.d(TAG, "(" + pt.getX() + ", " + pt.getY() + ") on (" + W + ", " + H + ")");
//            onView = true;//pt.getX() > - W / 2 && pt.getX() < W / 2 && pt.getY() > - H / 2 && pt.getY() < H / 2;
//            onView = ranges[i] < MAX_RANGE;

            scale = ranges[i];

            if (scale < 1.0)
                scale = 1.0f;
//            else
//                scale = (float)Math.sqrt(scale);

            draw = ((x - xp) * (x - xp) + (y - yp) * (y - yp))
                    > (scale / (this.laserScanDetail * this.laserScanDetail)) * MIN_DISTANCE_SQUARED;

            if (i == 0 || i == ranges.length - 1 || draw) {
                vertexBackBuffer.put(x);
                vertexBackBuffer.put(y);
                vertexBackBuffer.put(0.0f);

                // Color
                vertexBackBuffer.put(p * LASER_SCAN_COLOR.getRed() + (1.0f - p));
                vertexBackBuffer.put(p * LASER_SCAN_COLOR.getGreen());
                vertexBackBuffer.put(p * LASER_SCAN_COLOR.getBlue());
                vertexBackBuffer.put(0.1f);

                xp = x;
                yp = y;
                ++num;
            }

            angle += angleIncrement;// * stride;
        }

        vertexBackBuffer.rewind();
        vertexBackBuffer.limit(num * (3 + 4));

        synchronized (mutex) {
            FloatBuffer tmp = vertexFrontBuffer;
            vertexFrontBuffer = vertexBackBuffer;
            vertexBackBuffer = tmp;
        }
    }

    /**
     * Returns this layers' GraphName
     *
     * @return The GraphName of this Layer
     */
    @Override
    public GraphName getFrame() {
        return frame;
    }

    /*
     * Draws the way points.
     */
    private void drawWayPoints(GL10 gl) {

        FloatBuffer b;
        PointF res = new PointF();

        // Draw the waypoints
        gl.glEnable(GL10.GL_DEPTH_TEST);
        gl.glDepthFunc(GL10.GL_GEQUAL);

        // Lock on waypoints to prevent modifications while reading
        synchronized (controlApp.getWaypoints()) {

            b = Vertices.allocateBuffer(3 * controlApp.getWaypoints().size());
            b.rewind();

            for (Vector3 pt : controlApp.getWaypoints()) {

                drawPoint(gl, pt.getX(), pt.getY(), 0.0f, 0, res);

                b.put(res.x);
                b.put(res.y);
                b.put(0.0f);
            }
        }

        b.rewind();

        // Draw the path
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);

        gl.glLineWidth(8.0f);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 3 * 4, b);
        gl.glDrawArrays(GL10.GL_LINE_STRIP, 0, b.capacity() / 3);

        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);

        gl.glDisable(GL10.GL_DEPTH_TEST);

        // Draw the Waypoints
        b.rewind();
        for (int i = 0; i < b.limit(); i += 3) {
            Utils.drawPoint(gl, b.get(i), b.get(i + 1), 24.0f, i == 0 ? 0xFF22CC33 : 0xFF2233CC);
        }
    }

    /*
     * Draws a point specified in world space.
     * Pass a non-null PointF to result to grab the converted point instead of drawing it.
     */
    private static void drawPoint(GL10 gl, double x, double y, float size, int color, PointF result) {
        double rx = RobotController.getX();
        double ry = RobotController.getY();

        // Calculations
        double dir = Utils.pointDirection(rx, ry, x, y);
        dir = Utils.angleDifference(RobotController.getHeading(), dir);
        double len = Utils.distance(rx, ry, x, y);

        x = Math.cos(dir) * len;
        y = Math.sin(dir) * len;

        if (result != null) {
            result.set((float) x, (float) y);
        } else {
            Utils.drawPoint(gl, (float) x, (float) y, size, color);
        }
    }

    /*
     * Converts a screen point to world space.
     */
    private static Vector3 screenToWorld(double sx, double sy) {
        double rx = RobotController.getX();
        double ry = RobotController.getY();

        double cos = -Math.cos(RobotController.getHeading());
        double sin = -Math.sin(RobotController.getHeading());

        double xx = sx * cos - sy * sin;
        double yy = sx * sin + sy * cos;

        sx = rx + xx;
        sy = ry + yy;

        return new Vector3(sx, sy, 0.0);
    }
}