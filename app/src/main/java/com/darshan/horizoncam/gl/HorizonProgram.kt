package com.darshan.horizoncam.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Shader that samples the camera's external OES texture through an affine mapping.
 * The same program draws the recorded output (pure mapping) and the preview
 * (mapping + dimmed outside area + frame border + optional grid).
 */
class HorizonProgram {
    private val program: Int
    private val aPos: Int
    private val uMap: Int
    private val uFrame: Int
    private val uTex: Int
    private val uOutside: Int
    private val uLine: Int
    private val uBorder: Int
    private val uGrid: Int
    private val uBorderColor: Int
    private val uDx: Int
    private val uDy: Int
    private val uTaps: Int
    private val uSharpen: Int
    private val sTex: Int

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    init {
        program = createProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uMap = GLES20.glGetUniformLocation(program, "uMap")
        uFrame = GLES20.glGetUniformLocation(program, "uFrame")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uOutside = GLES20.glGetUniformLocation(program, "uOutside")
        uLine = GLES20.glGetUniformLocation(program, "uLine")
        uBorder = GLES20.glGetUniformLocation(program, "uBorder")
        uGrid = GLES20.glGetUniformLocation(program, "uGrid")
        uBorderColor = GLES20.glGetUniformLocation(program, "uBorderColor")
        uDx = GLES20.glGetUniformLocation(program, "uDx")
        uDy = GLES20.glGetUniformLocation(program, "uDy")
        uTaps = GLES20.glGetUniformLocation(program, "uTaps")
        uSharpen = GLES20.glGetUniformLocation(program, "uSharpen")
        sTex = GLES20.glGetUniformLocation(program, "sTex")
    }

    class Params {
        val map = FloatArray(16)
        val frame = FloatArray(16)
        var outside = 1f
        var lineX = 0f
        var lineY = 0f
        var border = 0f
        var grid = 0f
        var borderR = 1f
        var borderG = 1f
        var borderB = 1f
        /** Texture-space step of one output pixel along x / y (for anti-aliasing and sharpening). */
        val dx = FloatArray(2)
        val dy = FloatArray(2)
        /** 1 = single bilinear tap, 2 = 2x2 supersampling, 3 = 3x3 supersampling. */
        var taps = 1f
        /** Unsharp-mask strength (0 = off). */
        var sharpen = 0f
    }

    fun draw(textureId: Int, texMatrix: FloatArray, p: Params) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(sTex, 0)
        GLES20.glUniformMatrix4fv(uMap, 1, false, p.map, 0)
        GLES20.glUniformMatrix4fv(uFrame, 1, false, p.frame, 0)
        GLES20.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0)
        GLES20.glUniform1f(uOutside, p.outside)
        GLES20.glUniform2f(uLine, p.lineX, p.lineY)
        GLES20.glUniform1f(uBorder, p.border)
        GLES20.glUniform1f(uGrid, p.grid)
        GLES20.glUniform3f(uBorderColor, p.borderR, p.borderG, p.borderB)
        GLES20.glUniform2f(uDx, p.dx[0], p.dx[1])
        GLES20.glUniform2f(uDy, p.dy[0], p.dy[1])
        GLES20.glUniform1f(uTaps, p.taps)
        GLES20.glUniform1f(uSharpen, p.sharpen)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    companion object {
        private const val VERTEX = """
uniform mat4 uMap;
uniform mat4 uFrame;
uniform mat4 uTex;
attribute vec4 aPos;
varying vec2 vTex;
varying vec2 vNat;
varying vec2 vFrame;
void main() {
    gl_Position = aPos;
    vec4 n = uMap * aPos;
    vNat = n.xy;
    vTex = (uTex * vec4(n.xy, 0.0, 1.0)).xy;
    vFrame = (uFrame * aPos).xy;
}
"""

        private const val FRAGMENT = """#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES sTex;
uniform float uOutside;
uniform vec2 uLine;
uniform float uBorder;
uniform float uGrid;
uniform vec3 uBorderColor;
uniform vec2 uDx;
uniform vec2 uDy;
uniform float uTaps;
uniform float uSharpen;
varying vec2 vTex;
varying vec2 vNat;
varying vec2 vFrame;

// Box-filtered sample over the output pixel's footprint: removes the aliasing/shimmer of
// plain bilinear sampling when a large camera frame is scaled down and rotated.
vec3 footprint(vec2 tc) {
    if (uTaps < 1.5) {
        return texture2D(sTex, tc).rgb;
    }
    if (uTaps < 2.5) {
        vec2 a = 0.25 * uDx;
        vec2 b = 0.25 * uDy;
        return 0.25 * (texture2D(sTex, tc + a + b).rgb + texture2D(sTex, tc + a - b).rgb
                     + texture2D(sTex, tc - a + b).rgb + texture2D(sTex, tc - a - b).rgb);
    }
    vec2 a3 = uDx / 3.0;
    vec2 b3 = uDy / 3.0;
    vec3 acc = vec3(0.0);
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            acc += texture2D(sTex, tc + float(i) * a3 + float(j) * b3).rgb;
        }
    }
    return acc / 9.0;
}

void main() {
    vec3 col = footprint(vTex);
    if (uSharpen > 0.0) {
        // Gentle unsharp mask at one output pixel radius restores detail lost to resampling.
        vec3 blur = 0.25 * (texture2D(sTex, vTex + uDx).rgb + texture2D(sTex, vTex - uDx).rgb
                          + texture2D(sTex, vTex + uDy).rgb + texture2D(sTex, vTex - uDy).rgb);
        col = clamp(col + uSharpen * (col - blur), 0.0, 1.0);
    }
    float inImg = step(0.0, vNat.x) * step(vNat.x, 1.0) * step(0.0, vNat.y) * step(vNat.y, 1.0);
    col *= inImg;
    vec2 a = abs(vFrame);
    float inside = step(a.x, 1.0) * step(a.y, 1.0);
    col *= mix(uOutside, 1.0, inside);
    float nearX = step(1.0 - uLine.x, a.x) * step(a.x, 1.0 + uLine.x) * step(a.y, 1.0 + uLine.y);
    float nearY = step(1.0 - uLine.y, a.y) * step(a.y, 1.0 + uLine.y) * step(a.x, 1.0 + uLine.x);
    float border = max(nearX, nearY) * uBorder;
    float g1 = step(abs(a.x - 0.333333), uLine.x * 0.6);
    float g2 = step(abs(a.y - 0.333333), uLine.y * 0.6);
    float grid = max(g1, g2) * inside * uGrid;
    col = mix(col, vec3(1.0), grid * 0.45);
    col = mix(col, uBorderColor, border * 0.85);
    gl_FragColor = vec4(col, 1.0);
}
"""

        fun createProgram(vs: String, fs: String): Int {
            val v = loadShader(GLES20.GL_VERTEX_SHADER, vs)
            val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetProgramInfoLog(p)
                GLES20.glDeleteProgram(p)
                throw RuntimeException("Program link failed: $log")
            }
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            return p
        }

        private fun loadShader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw RuntimeException("Shader compile failed: $log")
            }
            return s
        }

        fun createOesTexture(): Int {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            return t[0]
        }
    }
}
