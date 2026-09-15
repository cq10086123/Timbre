package voice.core.common.comparator

import android.net.Uri

object NaturalOrderComparator {

  val stringComparator: Comparator<String> = NaturalComparator

  val uriComparator = object : Comparator<Uri> {
    override fun compare(
      lhs: Uri,
      rhs: Uri,
    ): Int {
      val lhsSegments = lhs.pathSegments.flatMap { it.split("/") }
      val rhsSegments = rhs.pathSegments.flatMap { it.split("/") }

      // compare every segment naturally, so a file in a sub folder is ordered
      // by name relative to files in the parent folder instead of always
      // sorting before/after them purely based on path depth.
      val minSize = minOf(lhsSegments.size, rhsSegments.size)
      for (index in 0 until minSize) {
        val diff = stringComparator.compare(lhsSegments[index], rhsSegments[index])
        if (diff != 0) {
          return diff
        }
      }
      return lhsSegments.size - rhsSegments.size
    }
  }
}

fun Set<String>.sortedNaturally(): List<String> = sortedWith(NaturalOrderComparator.stringComparator)
