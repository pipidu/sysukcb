package cn.sysu.kcb.domain

import cn.sysu.kcb.data.local.CourseEntity

/**
 * 再次从教务导入时，跳过与本地已改课程对应的新行，避免叠一份。
 * 先按教学班 + 星期 + 起始节次配对，再按同一教学班把改过星期或节次的课一对一配对，
 * 没有教学班时退回课名 + 星期 + 起始节次。
 */
fun incomingImportedToInsert(
    incoming: List<CourseEntity>,
    retainedEdited: List<CourseEntity>,
): List<CourseEntity> {
    if (retainedEdited.isEmpty() || incoming.isEmpty()) return incoming
    val matchedRetained = BooleanArray(retainedEdited.size)
    val skipIncoming = BooleanArray(incoming.size)

    fun mark(incomingIndex: Int, retainedIndex: Int) {
        skipIncoming[incomingIndex] = true
        matchedRetained[retainedIndex] = true
    }

    for (i in incoming.indices) {
        val course = incoming[i]
        val classesId = course.classesId?.trim().orEmpty()
        if (classesId.isEmpty()) continue
        val retainedIndex = retainedEdited.indices.firstOrNull { index ->
            !matchedRetained[index] &&
                retainedEdited[index].classesId?.trim() == classesId &&
                retainedEdited[index].dayOfWeek == course.dayOfWeek &&
                retainedEdited[index].startPeriod == course.startPeriod
        } ?: continue
        mark(i, retainedIndex)
    }

    for (i in incoming.indices) {
        if (skipIncoming[i]) continue
        val classesId = incoming[i].classesId?.trim().orEmpty()
        if (classesId.isEmpty()) continue
        val retainedIndex = retainedEdited.indices.firstOrNull { index ->
            !matchedRetained[index] && retainedEdited[index].classesId?.trim() == classesId
        } ?: continue
        mark(i, retainedIndex)
    }

    for (i in incoming.indices) {
        if (skipIncoming[i]) continue
        val course = incoming[i]
        val retainedIndex = retainedEdited.indices.firstOrNull { index ->
            !matchedRetained[index] &&
                retainedEdited[index].courseName.trim() == course.courseName.trim() &&
                retainedEdited[index].dayOfWeek == course.dayOfWeek &&
                retainedEdited[index].startPeriod == course.startPeriod
        } ?: continue
        mark(i, retainedIndex)
    }

    return incoming.filterIndexed { index, _ -> !skipIncoming[index] }
}
