package dev.turjo.paperless.feature.tools

internal enum class ToolGroup(val title: Int) {
    ORGANIZE(R.string.tools_group_organize),
    SHRINK(R.string.tools_group_shrink),
    PRINT(R.string.tools_group_print),
    PROTECT(R.string.tools_group_protect),
    CONVERT(R.string.tools_group_convert),
}

internal enum class ToolCardInfo(
    val tool: Tool,
    val group: ToolGroup,
    val description: Int,
    val icon: Int,
    private val keywords: String,
    val title: Int = tool.title,
) {
    MERGE(Tool.MERGE, ToolGroup.ORGANIZE, R.string.tools_desc_merge, R.drawable.tools_merge,
        "combine join concatenate"),
    SPLIT(Tool.SPLIT, ToolGroup.ORGANIZE, R.string.tools_desc_split, R.drawable.tools_split,
        "extract separate divide"),
    PAGES(Tool.PAGES, ToolGroup.ORGANIZE, R.string.tools_desc_pages, R.drawable.tools_pages,
        "edit insert duplicate move rearrange"),
    COMPRESS(Tool.COMPRESS, ToolGroup.SHRINK, R.string.tools_desc_compress, R.drawable.tools_compress,
        "smaller shrink size reduce optimise optimize"),
    WIDTH(Tool.WIDTH, ToolGroup.SHRINK, R.string.tools_desc_width, R.drawable.tools_width,
        "resize uniform normalize align dimensions"),
    GRAYSCALE(Tool.GRAYSCALE, ToolGroup.SHRINK, R.string.tools_desc_grayscale, R.drawable.tools_grayscale,
        "gray grey monochrome black white color"),
    SANITIZE(Tool.SANITIZE, ToolGroup.SHRINK, R.string.tools_desc_sanitize, R.drawable.tools_sanitize,
        "clean scrub remove javascript privacy"),
    PRINT(Tool.PRINT, ToolGroup.PRINT, R.string.tools_desc_print, R.drawable.tools_print,
        "paper imposition sheet layout"),
    NUMBERS(Tool.NUMBERS, ToolGroup.PRINT, R.string.tools_desc_numbers, R.drawable.tools_numbers,
        "numbering paginate footer bates"),
    PASSWORD(Tool.PASSWORD, ToolGroup.PROTECT, R.string.tools_desc_password, R.drawable.tools_password,
        "encrypt decrypt lock protect security"),
    METADATA(Tool.METADATA, ToolGroup.PROTECT, R.string.tools_desc_metadata, R.drawable.tools_metadata,
        "properties info subject keywords"),
    FROM_IMAGES(Tool.FROM_IMAGES, ToolGroup.CONVERT, R.string.tools_desc_from_images, R.drawable.tools_from_images,
        "photo picture jpg jpeg png image scan", R.string.tools_landing_from_images),
    TO_IMAGES(Tool.TO_IMAGES, ToolGroup.CONVERT, R.string.tools_desc_to_images, R.drawable.tools_to_images,
        "export raster render picture jpeg", R.string.tools_landing_to_images),
    BATCH(Tool.BATCH, ToolGroup.CONVERT, R.string.tools_desc_batch, R.drawable.tools_batch,
        "bulk multiple automate folder"),
    ;

    fun matches(query: String, title: String, description: String): Boolean {
        val searchable = "$title $description $keywords"
        return query.trim().split(Regex("\\s+")).all { searchable.contains(it, ignoreCase = true) }
    }
}
