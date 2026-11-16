package com.example.utils

import org.json.JSONArray
import org.json.JSONObject

/** Points are normalized (0f..1f) relative to the image's displayed width/height, so the
 *  same overlay renders correctly regardless of view size or screen density. */
data class NormalizedPoint(val x: Float, val y: Float)

sealed class AnnotationShape {
    abstract val id: String
    abstract val colorArgb: Long

    data class Arrow(
        override val id: String,
        override val colorArgb: Long,
        val start: NormalizedPoint,
        val end: NormalizedPoint
    ) : AnnotationShape()

    data class TextLabel(
        override val id: String,
        override val colorArgb: Long,
        val position: NormalizedPoint,
        val text: String
    ) : AnnotationShape()

    data class Rectangle(
        override val id: String,
        override val colorArgb: Long,
        val topLeft: NormalizedPoint,
        val bottomRight: NormalizedPoint
    ) : AnnotationShape()

    data class Freehand(
        override val id: String,
        override val colorArgb: Long,
        val points: List<NormalizedPoint>
    ) : AnnotationShape()
}

/** Serializes the overlay list to/from [com.example.data.local.entity.PatientImageEntity.annotationsJson]. */
object AnnotationSerializer {

    fun serialize(shapes: List<AnnotationShape>): String {
        val array = JSONArray()
        shapes.forEach { shape ->
            val obj = JSONObject()
            obj.put("id", shape.id)
            obj.put("color", shape.colorArgb)
            when (shape) {
                is AnnotationShape.Arrow -> {
                    obj.put("type", "arrow")
                    obj.put("start", pointToJson(shape.start))
                    obj.put("end", pointToJson(shape.end))
                }
                is AnnotationShape.TextLabel -> {
                    obj.put("type", "text")
                    obj.put("position", pointToJson(shape.position))
                    obj.put("text", shape.text)
                }
                is AnnotationShape.Rectangle -> {
                    obj.put("type", "rectangle")
                    obj.put("topLeft", pointToJson(shape.topLeft))
                    obj.put("bottomRight", pointToJson(shape.bottomRight))
                }
                is AnnotationShape.Freehand -> {
                    obj.put("type", "freehand")
                    val pointsArray = JSONArray()
                    shape.points.forEach { pointsArray.put(pointToJson(it)) }
                    obj.put("points", pointsArray)
                }
            }
            array.put(obj)
        }
        return array.toString()
    }

    fun deserialize(json: String): List<AnnotationShape> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.getJSONObject(index)
                val id = obj.getString("id")
                val color = obj.optLong("color", 0xFFFF0000)
                when (obj.getString("type")) {
                    "arrow" -> AnnotationShape.Arrow(id, color, pointFromJson(obj.getJSONObject("start")), pointFromJson(obj.getJSONObject("end")))
                    "text" -> AnnotationShape.TextLabel(id, color, pointFromJson(obj.getJSONObject("position")), obj.getString("text"))
                    "rectangle" -> AnnotationShape.Rectangle(id, color, pointFromJson(obj.getJSONObject("topLeft")), pointFromJson(obj.getJSONObject("bottomRight")))
                    "freehand" -> {
                        val pointsArray = obj.getJSONArray("points")
                        val points = (0 until pointsArray.length()).map { pointFromJson(pointsArray.getJSONObject(it)) }
                        AnnotationShape.Freehand(id, color, points)
                    }
                    else -> null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pointToJson(point: NormalizedPoint) = JSONObject().apply {
        put("x", point.x)
        put("y", point.y)
    }

    private fun pointFromJson(obj: JSONObject) = NormalizedPoint(
        x = obj.getDouble("x").toFloat(),
        y = obj.getDouble("y").toFloat()
    )
}
