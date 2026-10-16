package com.kroryan.astrovia.sky

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader

/** Draws a [TileMesh] textured with a tile bitmap: one call per tile, vertices shared by all triangles. */
object TileMeshPainter {
    fun draw(canvas: Canvas, bmp: Bitmap, mesh: TileMesh, paint: Paint) {
        paint.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        try {
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES,
                mesh.positions.size, mesh.positions, 0,
                mesh.texCoords, 0,
                null, 0,
                mesh.indices, 0, mesh.indices.size,
                paint,
            )
        } finally {
            paint.shader = null
        }
    }
}
