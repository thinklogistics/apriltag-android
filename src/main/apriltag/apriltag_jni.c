#include <jni.h>

#include <android/bitmap.h>
#include <android/log.h>
#include <tgmath.h>

#include "apriltag.h"
#include "tag16h5.h"
#include "tag25h9.h"
#include "tag36h10.h"
#include "tag36h11.h"

#include "tagCircle21h7.h"
#include "tagCircle49h12.h"
#include "tagCustom48h12.h"
#include "tagStandard41h12.h"
#include "tagStandard52h13.h"
#include "apriltag_pose.h"

static struct {
    apriltag_detector_t *td;
    apriltag_family_t *tf;
    void(*tf_destroy)(apriltag_family_t*);

    jclass al_cls;
    jmethodID al_constructor, al_add;
    jclass ad_cls;
    jmethodID ad_constructor;
    jfieldID ad_id_field, ad_hamming_field, ad_c_field, ad_p_field, ad_pose_field; //add new field to calculate pose and pass.
} state;

JNIEXPORT void JNICALL Java_edu_umich_eecs_april_apriltag_ApriltagNative_native_1init
    (JNIEnv *env, jclass cls)
{
    // Just do method lookups once and cache the results

    // Get ArrayList methods
    jclass al_cls = (*env)->FindClass(env, "java/util/ArrayList");
    if (!al_cls) {
        __android_log_write(ANDROID_LOG_ERROR, "apriltag_jni",
                            "couldn't find ArrayList class");
        return;
    }
    state.al_cls = (*env)->NewGlobalRef(env, al_cls);

    state.al_constructor = (*env)->GetMethodID(env, al_cls, "<init>", "()V");
    state.al_add = (*env)->GetMethodID(env, al_cls, "add", "(Ljava/lang/Object;)Z");
    if (!state.al_constructor || !state.al_add) {
        __android_log_write(ANDROID_LOG_ERROR, "apriltag_jni",
                            "couldn't find ArrayList methods");
        return;
    }

    // Get ApriltagDetection methods
    jclass ad_cls = (*env)->FindClass(env, "edu/umich/eecs/april/apriltag/ApriltagDetection");
    if (!ad_cls) {
        __android_log_write(ANDROID_LOG_ERROR, "apriltag_jni",
                            "couldn't find ApriltagDetection class");
        return;
    }
    state.ad_cls = (*env)->NewGlobalRef(env, ad_cls);

    state.ad_constructor = (*env)->GetMethodID(env, ad_cls, "<init>", "()V");
    if (!state.ad_constructor) {
        __android_log_write(ANDROID_LOG_ERROR, "apriltag_jni",
                            "couldn't find ApriltagDetection constructor");
        return;
    }

    state.ad_id_field = (*env)->GetFieldID(env, ad_cls, "id", "I");
    state.ad_hamming_field = (*env)->GetFieldID(env, ad_cls, "hamming", "I");
    state.ad_c_field = (*env)->GetFieldID(env, ad_cls, "c", "[D");
    state.ad_p_field = (*env)->GetFieldID(env, ad_cls, "p", "[D");
    state.ad_pose_field = (*env)->GetFieldID(env, ad_cls, "pose", "[D");

    if (!state.ad_id_field ||
            !state.ad_hamming_field ||
            !state.ad_c_field ||
            !state.ad_p_field) {
        __android_log_write(ANDROID_LOG_ERROR, "apriltag_jni",
                            "couldn't find ApriltagDetection fields");
        return;
    }
}

/*
 * Class:     edu_umich_eecs_april_apriltag_ApriltagNative
 * Method:    yuv_to_rgb
 * Signature: ([BIILandroid/graphics/Bitmap;)V
 */
JNIEXPORT void JNICALL Java_edu_umich_eecs_april_apriltag_ApriltagNative_yuv_1to_1rgb
    (JNIEnv *env, jclass cls, jbyteArray _src, jint width, jint height, jobject _dst)
{
    // NV21 Format
    // width*height    luma (Y) bytes followed by
    // width*height/2  chroma (UV) bytes interleaved as V,U

    jbyte *src = (*env)->GetByteArrayElements(env, _src, NULL);
    jint *dst = NULL;
    AndroidBitmap_lockPixels(env, _dst, &dst);

    if (!dst) {
        __android_log_write(ANDROID_LOG_ERROR, "apriltag_jni",
                            "couldn't lock bitmap");
        return;
    }

    AndroidBitmapInfo bmpinfo;
    if (AndroidBitmap_getInfo(env, _dst, &bmpinfo) ||
        bmpinfo.width*bmpinfo.height != width*height ||
        bmpinfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        __android_log_print(ANDROID_LOG_ERROR, "apriltag_jni",
                            "incorrect bitmap format: %d x %d  %d",
                            bmpinfo.width, bmpinfo.height, bmpinfo.format);
        return;
    }

    int uvstart = width * height;
    for (int j = 0; j < height; j += 1) {
        for (int i = 0; i < width; i += 1) {
            int y = (unsigned char)src[j*width + i];
            int offset = uvstart + (j >> 1)*width + (i & ~1);
            int u = (unsigned char)src[offset + 1];
            int v = (unsigned char)src[offset + 0];

            y = y < 16 ? 16 : y;

            int a0 = 1192 * (y - 16);
            int a1 = 1634 * (v - 128);
            int a2 = 832 * (v - 128);
            int a3 = 400 * (u - 128);
            int a4 = 2066 * (u - 128);

            int r = (a0 + a1) >> 10;
            int g = (a0 - a2 - a3) >> 10;
            int b = (a0 + a4) >> 10;

            r = r < 0 ? 0 : (r > 255 ? 255 : r);
            g = g < 0 ? 0 : (g > 255 ? 255 : g);
            b = b < 0 ? 0 : (b > 255 ? 255 : b);

            // Output image in portrait orientation
            dst[(i+1)*height - j-1] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
    }

    (*env)->ReleaseByteArrayElements(env, _src, src, 0);
    AndroidBitmap_unlockPixels(env, _dst);
}

/*
 * Class:     edu_umich_eecs_april_apriltag_ApriltagNative
 * Method:    apriltag_init
 * Signature: (Ljava/lang/String;IDDI)V
 */
JNIEXPORT void JNICALL Java_edu_umich_eecs_april_apriltag_ApriltagNative_apriltag_1init
        (JNIEnv *env, jclass cls, jstring _tfname, jint errorbits, jdouble decimate,
         jdouble sigma, jint nthreads) {
    // Do cleanup in case we're already initialized
    if (state.td) {
        apriltag_detector_destroy(state.td);
        state.td = NULL;
    }
    if (state.tf) {
        state.tf_destroy(state.tf);
        state.tf = NULL;
    }

    // Initialize state
    const char *tfname = (*env)->GetStringUTFChars(env, _tfname, NULL);

    if (!strcmp(tfname, "tag36h11")) {
        state.tf = tag36h11_create();
        state.tf_destroy = tag36h11_destroy;
    } else if (!strcmp(tfname, "tag36h10")) {
        state.tf = tag36h10_create();
        state.tf_destroy = tag36h10_destroy;
    }
    else if (!strcmp(tfname, "tag16h5")) {
        state.tf = tag16h5_create();
        state.tf_destroy = tag16h5_destroy;
    }  else if (!strcmp(tfname, "tag25h9")) {
        state.tf = tag25h9_create();
        state.tf_destroy = tag25h9_destroy;
    }else if (!strcmp(tfname, "tag21h7")) {
        state.tf = tagCircle21h7_create();
        state.tf_destroy = tagCircle21h7_destroy;
    }
    else if (!strcmp(tfname, "tag48h12")) {
        state.tf = tagCustom48h12_create();
        state.tf_destroy = tagCustom48h12_destroy;
    }else if (!strcmp(tfname, "tag41h12")) {
        state.tf = tagStandard41h12_create();
        state.tf_destroy = tagStandard41h12_destroy;
    }else if (!strcmp(tfname, "tag49h12")) {
        state.tf = tagCircle49h12_create();
        state.tf_destroy = tagCircle49h12_destroy;
    }
    else if (!strcmp(tfname, "tag52h13")) {
        state.tf = tagStandard52h13_create();
        state.tf_destroy = tagStandard52h13_destroy;
    }else {
        __android_log_print(ANDROID_LOG_ERROR, "apriltag_jni",
                            "invalid tag family: %s", tfname);
        (*env)->ReleaseStringUTFChars(env, _tfname, tfname);
        return;
    }
    (*env)->ReleaseStringUTFChars(env, _tfname, tfname);

    state.td = apriltag_detector_create();
    apriltag_detector_add_family_bits(state.td, state.tf, errorbits);
    state.td->quad_decimate = decimate;
    state.td->quad_sigma = sigma;
    state.td->nthreads = nthreads;
}

/*
 * Class:     edu_umich_eecs_april_apriltag_ApriltagNative
 * Method:    apriltag_detect_yuv
 * Signature: ([BII)Ljava/util/ArrayList;
 */
JNIEXPORT jobject JNICALL Java_edu_umich_eecs_april_apriltag_ApriltagNative_apriltag_1detect_1yuv
        (JNIEnv *env, jclass cls, jbyteArray _buf, jint width, jint height) {
    // If not initialized, init with default settings
    if (!state.td) {
        state.tf = tag36h11_create();
        state.td = apriltag_detector_create();
        apriltag_detector_add_family_bits(state.td, state.tf, 2);
        state.td->quad_decimate = 2.0;
        state.td->quad_sigma = 0.0;
        state.td->nthreads = 4;
        __android_log_write(ANDROID_LOG_INFO, "apriltag_jni",
                            "using default parameters");
    }

    // Use the luma channel (the first width*height elements)
    // as grayscale input image
    jbyte *buf = (*env)->GetByteArrayElements(env, _buf, NULL);
    image_u8_t im = {
            .buf = (uint8_t*)buf,
            .height = height,
            .width = width,
            .stride = width
    };
    zarray_t *detections = apriltag_detector_detect(state.td, &im);
    (*env)->ReleaseByteArrayElements(env, _buf, buf, 0);

    // al = new ArrayList();
    jobject al = (*env)->NewObject(env, state.al_cls, state.al_constructor);
    for (int i = 0; i < zarray_size(detections); i += 1) {
        apriltag_detection_t *det;
        zarray_get(detections, i, &det);

        //pose detection
        apriltag_detection_info_t info;
        info.det = det;
        info.tagsize = 0.06; //real tag size in meter, we assume 6cm
        info.fx = width;
        info.fy = height;
        info.cx = width/2;
        info.cy = height/2;

        apriltag_pose_t pose;

        //calculate pose
        estimate_tag_pose(&info, &pose);

        // ad = new ApriltagDetection();
        jobject ad = (*env)->NewObject(env, state.ad_cls, state.ad_constructor);
        (*env)->SetIntField(env, ad, state.ad_id_field, det->id);
        (*env)->SetIntField(env, ad, state.ad_hamming_field, det->hamming);
        jdoubleArray ad_c = (*env)->GetObjectField(env, ad, state.ad_c_field);
        (*env)->SetDoubleArrayRegion(env, ad_c, 0, 2, det->c);
        jdoubleArray ad_p = (*env)->GetObjectField(env, ad, state.ad_p_field);
        (*env)->SetDoubleArrayRegion(env, ad_p, 0, 8, (double*)det->p);

        //pass pose info as array
        jdoubleArray ad_pose = (*env)->GetObjectField(env, ad, state.ad_pose_field);
        (*env)->SetDoubleArrayRegion(env, ad_pose, 0, 9, (double *)pose.R->data);

        // al.add(ad);
        (*env)->CallBooleanMethod(env, al, state.al_add, ad);

        // Need to respect the local reference limit
        (*env)->DeleteLocalRef(env, ad);
        (*env)->DeleteLocalRef(env, ad_c);
        (*env)->DeleteLocalRef(env, ad_p);
    }

    // Cleanup
    apriltag_detections_destroy(detections);

    return al;
}
