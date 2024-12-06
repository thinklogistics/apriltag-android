package edu.umich.eecs.april.apriltag;

import static java.lang.Math.PI;
import static java.lang.Math.atan2;
import static java.lang.Math.sqrt;

public class ApriltagDetection {
    // The decoded ID of the tag
    public int id;

    // How many error bits were corrected? Note: accepting large numbers of
    // corrected errors leads to greatly increased false positive rates.
    // NOTE: As of this implementation, the detector cannot detect tags with
    // a hamming distance greater than 2.
    public int hamming;

    // The center of the detection in image pixel coordinates.
    public double[] c = new double[2];

    // The corners of the tag in image pixel coordinates. These always
    // wrap counter-clock wise around the tag.
    // Flattened to [x0 y0 x1 y1 ...] for JNI convenience
    public double[] p = new double[8];

    // rotation matrix, represents the rotation of the tag relative to camera.
    // Flattened to [r11, r12, r13, r21, r22, r23, r31, r32, r33] for JNI convenience
    public double[] pose = new double[9];

    //calculate pitch, x-axis rotation angle
    public double getPitch() {
        double  r11 = pose[0];
        double  r21 = pose[3];
        double  r31 = pose[6];

        double pitch = atan2(-r31, sqrt(r11 * r11 + r21 * r21));

        pitch = pitch * 180 / PI;

        if (pitch < 0) {
            pitch += 360.0;
        }

        return pitch;
    }

    //calculate oll, y-axis rotation angle
    public double getRoll() {
        double  r21 = pose[3];
        double  r22 = pose[4];

        double roll = atan2(r21, r22);

        roll = roll * 180 / PI;

        if (roll < 0) {
            roll += 360.0;
        }

        return roll;
    }
}
