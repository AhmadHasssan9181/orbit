package ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp


object OrbitIcons {
    private var _textChannel: ImageVector? = null
    val TextChannel: ImageVector
        get() = _textChannel ?: discordIcon(
            name = "TextChannel",
            viewportSize = 24f,
            paths = listOf(
                CollectionPath(
                    data = "M5.886 21l1.114-4.457H3v-2.086h4.514l1.029-4.114H4.543V8.257h4.571L10.229 3.8h2.086l-1.114 4.457h4.085l1.115-4.457h2.085l-1.114 4.457H21v2.086h-4.514l-1.029 4.114h4.086V16.543h-4.572L13.857 21h-2.086l1.115-4.457H8.8l-1.114 4.457H5.886zm3.429-6.543h4.085l1.029-4.114H10.343l-1.028 4.114z",
                    fillType = PathFillType.EvenOdd
                ),
            )
        ).also { _textChannel = it }

    private var _voiceChannel: ImageVector? = null
    val VoiceChannel: ImageVector
        get() = _voiceChannel ?: discordIcon(
            name = "VoiceChannel",
            viewportSize = 24f,
            paths = listOf(
                CollectionPath(
                    data = "M11.383 3.079C11.009 2.925 10.579 3.01 10.293 3.296L6 7.589H3C2.447 7.589 2 8.037 2 8.589v6.822C2 15.963 2.447 16.411 3 16.411h3l4.293 4.293C10.579 20.99 11.009 21.075 11.383 20.921 11.757 20.767 12 20.404 12 20V4c0-.404-.243-.767-.617-.921zM14 6v2c2.209 0 4 1.791 4 4s-1.791 4-4 4v2c3.314 0 6-2.686 6-6s-2.686-6-6-6zm0 4v4c1.105 0 2-.895 2-2s-.895-2-2-2z"
                ),
            )
        ).also { _voiceChannel = it }

    private var _video: ImageVector? = null
    val Video: ImageVector
        get() = _video ?: collectionIcon(
            name = "Video",
            originX = 80f,
            originY = 831f,
            paths = listOf(
                CollectionPath(
                    data = """M158.104 856.371C156.924 855.652 155.448 855.593 154.212 856.198L144 861.215V851.857C144 847.524 140.412 844 136 844H88C83.588 844 80 847.524 80 851.857V891.143C80 895.48 83.588 899 88 899H136C140.412 899 144 895.48 144 891.143V881.785L154.212 886.798C154.776 887.077 155.388 887.214 156 887.214C156.732 887.214 157.46 887.014 158.104 886.629C159.28 885.91 160 884.649 160 883.286V859.714C160 858.351 159.28 857.09 158.104 856.371Z"""
                ),
            )
        ).also { _video = it }

    private var _videoOff: ImageVector? = null
    val VideoOff: ImageVector
        get() = _videoOff ?: collectionIcon(
            name = "VideoOff",
            originX = 195f,
            originY = 831f,
            paths = listOf(
                CollectionPath(
                    data = """M218.831 898.237H252.911C256.972 898.237 260.274 894.934 260.274 890.863V882.08L269.673 886.785C270.192 887.047 270.755 887.176 271.319 887.176C271.992 887.176 272.662 886.988 273.255 886.627C274.337 885.952 275 884.768 275 883.489V861.367C275 860.087 274.337 858.904 273.255 858.229C272.169 857.554 270.81 857.499 269.673 858.067L260.274 862.775V856.731L218.831 898.237ZM239.133 846.618H208.733C204.673 846.618 201.37 849.926 201.37 853.992V884.438L239.133 846.618Z"""
                ),
                CollectionPath(
                    data = """M261.683 834L194 901.786L199.206 907L266.89 839.214L261.683 834Z"""
                ),
            )
        ).also { _videoOff = it }

    private var _mic: ImageVector? = null
    val Mic: ImageVector
        get() = _mic ?: collectionIcon(
            name = "Mic",
            originX = 310f,
            originY = 831f,
            paths = listOf(
                CollectionPath("""M345.556 839.944C345.556 837.766 347.347 836 349.557 836H350.446C352.655 836 354.446 837.766 354.446 839.944V903.056C354.446 905.234 352.655 907 350.446 907H349.557C347.347 907 345.556 905.234 345.556 903.056V839.944Z"""),
                CollectionPath("""M327.776 848.819C327.776 846.641 329.567 844.875 331.777 844.875H332.666C334.875 844.875 336.666 846.641 336.666 848.819V894.181C336.666 896.359 334.875 898.125 332.666 898.125H331.777C329.567 898.125 327.776 896.359 327.776 894.181V848.819Z"""),
                CollectionPath("""M314.89 884.813C317.099 884.813 318.89 883.047 318.89 880.868V862.132C318.89 859.954 317.099 858.188 314.89 858.188H314.001C311.791 858.188 310 859.954 310 862.132V880.868C310 883.047 311.791 884.813 314.001 884.813H314.89Z"""),
                CollectionPath("""M390 876.431C390 878.609 388.208 880.375 385.999 880.375H385.11C382.9 880.375 381.109 878.609 381.109 876.431V866.57C381.109 864.391 382.9 862.625 385.11 862.625H385.999C388.208 862.625 390 864.391 390 866.57V876.431Z"""),
                CollectionPath("""M367.334 853.75C365.124 853.75 363.333 855.516 363.333 857.694V885.306C363.333 887.484 365.124 889.25 367.334 889.25H368.223C370.432 889.25 372.223 887.484 372.223 885.306V857.694C372.223 855.516 370.432 853.75 368.223 853.75H367.334Z"""),
            )
        ).also { _mic = it }

    private var _micOff: ImageVector? = null
    val MicOff: ImageVector
        get() = _micOff ?: collectionIcon(
            name = "MicOff",
            originX = 425f,
            originY = 831f,
            paths = listOf(
                CollectionPath("""M478.318 873.402L487.206 864.637V885.306C487.206 887.484 485.415 889.25 483.206 889.25H482.317C480.108 889.25 478.318 887.484 478.318 885.306V873.402Z"""),
                CollectionPath("""M469.436 852.066V839.944C469.436 837.766 467.645 836 465.436 836H464.547C462.338 836 460.547 837.766 460.547 839.944V860.832L469.436 852.066Z"""),
                CollectionPath("""M460.547 890.935L460.842 890.642L469.436 882.167V903.056C469.436 905.234 467.645 907 465.436 907H464.547C462.338 907 460.547 905.234 460.547 903.056V890.935Z"""),
                CollectionPath("""M451.66 869.598L442.772 878.363V848.819C442.772 846.641 444.562 844.875 446.771 844.875H447.66C449.869 844.875 451.66 846.641 451.66 848.819V869.598Z"""),
                CollectionPath("""M433.888 880.868C433.888 883.047 432.098 884.813 429.889 884.813H429C426.791 884.813 425 883.047 425 880.868V862.132C425 859.953 426.791 858.188 429 858.188H429.889C432.098 858.188 433.888 859.953 433.888 862.132V880.868Z"""),
                CollectionPath("""M505 876.431C505 878.609 503.209 880.375 501 880.375H500.112C497.903 880.375 496.112 878.609 496.112 876.431V866.569C496.112 864.391 497.903 862.625 500.112 862.625H501C503.209 862.625 505 864.391 505 866.569V876.431Z"""),
                CollectionPath("""M500.986 841.009L495.907 836L428.992 901.99L434.071 907L450.83 890.512L455.75 885.621L500.986 841.009Z"""),
            )
        ).also { _micOff = it }

    private var _screenShare: ImageVector? = null
    val ScreenShare: ImageVector
        get() = _screenShare ?: collectionIcon(
            name = "ScreenShare",
            originX = 655f,
            originY = 561f,
            paths = listOf(
                CollectionPath(
                    data = """M663 563.505C658.588 563.505 655 567.045 655 571.399V614.814C655 619.171 658.588 622.708 663 622.708H691V630.601H675V638.495H715V630.601H699V622.708H727C731.412 622.708 735 619.171 735 614.814V571.399C735 567.045 731.412 563.505 727 563.505H663ZM671 612.84C677.672 603.615 686.456 599.421 699.8 599.421V610.226L719 591.774L699.8 573.372V583.88C681.128 586.545 673.688 599.668 671 612.84Z""",
                    fillType = PathFillType.EvenOdd
                ),
            )
        ).also { _screenShare = it }

    private var _screenShareOff: ImageVector? = null
    val ScreenShareOff: ImageVector
        get() = _screenShareOff ?: collectionIcon(
            name = "ScreenShareOff",
            originX = 80f,
            originY = 698f,
            paths = listOf(
                CollectionPath("""M88 698.629C83.588 698.629 80 702.157 80 706.496V749.768C80 754.111 83.588 757.636 88 757.636H116V765.503H100V773.371H140V765.503H124V757.636H152C156.412 757.636 160 754.111 160 749.768V706.496C160 702.157 156.412 698.629 152 698.629H88ZM130.193 712.397L136 718.108L125.786 728.112L136 738.157L130.193 743.867L120.02 733.823L109.807 743.867L104 738.157L114.214 728.112L104 718.108L109.807 712.397L120.02 722.442L130.193 712.397Z"""),
            )
        ).also { _screenShareOff = it }

    private var _call: ImageVector? = null
    val Call: ImageVector
        get() = _call ?: collectionIcon(
            name = "Call",
            originX = 425f,
            originY = 966f,
            paths = listOf(
                CollectionPath("""M462.895 974.423V966.002C486.116 966.002 505 984.89 505 1008.11H496.579C496.579 989.534 481.467 974.423 462.895 974.423ZM488.158 1008.11H479.737C479.737 998.823 472.183 991.265 462.895 991.265V982.844C476.827 982.844 488.158 994.174 488.158 1008.11ZM462.895 999.686V1008.11H471.316C471.316 1003.46 467.547 999.686 462.895 999.686ZM475.526 1020.74H492.368C494.697 1020.74 496.579 1022.62 496.579 1024.95V1041.79C496.579 1044.12 494.697 1046 492.368 1046H471.316C445.737 1046 425 1025.26 425 999.686V978.633C425 976.305 426.886 974.423 429.211 974.423H446.053C448.381 974.423 450.263 976.305 450.263 978.633V995.475C450.263 997.804 448.381 999.686 446.053 999.686H441.842C442.107 1016.27 454.474 1029.16 471.316 1029.16V1024.95C471.316 1022.62 473.198 1020.74 475.526 1020.74Z"""),
            )
        ).also { _call = it }

    private var _callEnd: ImageVector? = null
    val CallEnd: ImageVector
        get() = _callEnd ?: collectionIcon(
            name = "CallEnd",
            originX = 540f,
            originY = 966f,
            paths = listOf(
                CollectionPath("""M613.231 966.002L620 972.774L608.064 984.711L620 996.647L613.231 1003.42L601.291 991.483L589.354 1003.42L582.586 996.647L594.522 984.711L582.586 972.774L589.354 966.002L601.291 977.938L613.231 966.002ZM601.291 1046H582.138C558.866 1046 540 1027.14 540 1003.86V984.711C540 982.592 541.716 980.88 543.831 980.88H559.154C561.272 980.88 562.984 982.592 562.984 984.711V1000.03C562.984 1002.15 561.272 1003.86 559.154 1003.86H555.323C555.564 1018.95 566.815 1030.68 582.138 1030.68V1026.85C582.138 1024.73 583.85 1023.02 585.968 1023.02H601.291C603.41 1023.02 605.122 1024.73 605.122 1026.85V1042.17C605.122 1044.29 603.41 1046 601.291 1046Z"""),
            )
        ).also { _callEnd = it }

    private var _add: ImageVector? = null
    val Add: ImageVector
        get() = _add ?: collectionIcon(
            name = "Add",
            originX = 540f,
            originY = 1359f,
            paths = listOf(
                CollectionPath("""M580 1359C557.944 1359 540 1376.95 540 1399C540 1421.06 557.944 1439 580 1439C602.056 1439 620 1421.06 620 1399C620 1376.95 602.056 1359 580 1359ZM600 1403H584V1419H576V1403H560V1395H576V1379H584V1395H600V1403Z"""),
            )
        ).also { _add = it }

    private var _emoji: ImageVector? = null
    val Emoji: ImageVector
        get() = _emoji ?: collectionIcon(
            name = "Emoji",
            originX = 195f,
            originY = 1887f,
            paths = listOf(
                CollectionPath(
                    data = """M235.46 1887.01C235.307 1887 235.154 1887 235 1887C212.908 1887 195 1904.91 195 1927C195 1949.09 212.908 1967 235 1967C257.092 1967 275 1949.09 275 1927C275 1926.85 274.999 1926.7 274.997 1926.54C271.753 1927.7 268.258 1928.33 264.617 1928.33C247.529 1928.33 233.676 1914.47 233.676 1897.39C233.676 1893.75 234.305 1890.25 235.46 1887.01ZM218.684 1926.21C218.684 1929.85 215.739 1932.79 212.105 1932.79C208.472 1932.79 205.526 1929.85 205.526 1926.21C205.526 1922.58 208.472 1919.63 212.105 1919.63C215.739 1919.63 218.684 1922.58 218.684 1926.21ZM229.087 1937.07C228.544 1934.96 226.391 1933.69 224.28 1934.23C222.169 1934.78 220.898 1936.93 221.441 1939.04C223.048 1945.28 228.709 1949.9 235.459 1949.9C242.208 1949.9 247.87 1945.28 249.476 1939.04C250.02 1936.93 248.749 1934.78 246.637 1934.23C244.526 1933.69 242.374 1934.96 241.831 1937.07C241.1 1939.91 238.519 1942 235.459 1942C232.399 1942 229.818 1939.91 229.087 1937.07Z""",
                    fillType = PathFillType.EvenOdd
                ),
                CollectionPath("""M261 1887C258.791 1887 257 1888.79 257 1891V1897H251C248.791 1897 247 1898.79 247 1901C247 1903.21 248.791 1905 251 1905H257V1911C257 1913.21 258.791 1915 261 1915C263.209 1915 265 1913.21 265 1911V1905H271C273.209 1905 275 1903.21 275 1901C275 1898.79 273.209 1897 271 1897H265V1891C265 1888.79 263.209 1887 261 1887Z"""),
            )
        ).also { _emoji = it }

    private var _reply: ImageVector? = null
    val Reply: ImageVector
        get() = _reply ?: collectionIcon(
            name = "Reply",
            originX = 425f,
            originY = 1887f,
            paths = listOf(
                CollectionPath("""M456.722 1910.67V1892L426 1924.67L456.722 1957.34V1938.2C478.667 1938.2 494.028 1945.67 505 1962C500.611 1938.67 487.444 1915.34 456.722 1910.67Z"""),
            )
        ).also { _reply = it }

    private var _gif: ImageVector? = null
    val Gif: ImageVector
        get() = _gif ?: collectionIcon(
            name = "Gif",
            originX = 425f,
            originY = 1494f,
            paths = listOf(
                CollectionPath("""M431.667 1499C427.985 1499 425 1502.09 425 1505.9V1561.1C425 1564.91 427.985 1568 431.667 1568H498.333C502.015 1568 505 1564.91 505 1561.1V1505.9C505 1502.09 502.015 1499 498.333 1499H431.667ZM457.548 1531.6V1545.51C454.668 1547.45 451.268 1548.53 447.468 1548.53C438.708 1548.53 433.868 1542.57 433.868 1533.75C433.868 1524.89 439.108 1518.89 447.628 1518.89C451.028 1518.89 453.868 1519.71 456.068 1521L455.148 1527.17C453.068 1525.8 450.628 1524.72 447.788 1524.72C443.028 1524.72 440.708 1528.37 440.708 1533.71C440.708 1539.09 443.108 1542.86 447.868 1542.86C449.388 1542.86 450.468 1542.53 451.588 1541.95V1536.85H446.428V1531.6H457.548ZM463.494 1519.42H470.334V1547.99H463.494V1519.42ZM493.241 1519.42V1525.34H483.521V1531.18H491.201V1537.1H483.521V1547.99H476.721V1519.42H493.241Z"""),
            )
        ).also { _gif = it }

    private var _send: ImageVector? = null
    val Send: ImageVector
        get() = _send ?: collectionIcon(
            name = "Send",
            originX = 540f,
            originY = 1494f,
            paths = listOf(
                CollectionPath("""M581.454 1536.59L548.792 1539.77L540.198 1567.42C539.685 1569.05 540.188 1570.84 541.481 1571.96C542.77 1573.08 544.6 1573.32 546.133 1572.57L617.711 1537.7C619.112 1537.01 620 1535.58 620 1534.01C620 1532.45 619.111 1531.02 617.711 1530.33L546.181 1495.43C544.649 1494.68 542.819 1494.92 541.53 1496.04C540.237 1497.16 539.734 1498.94 540.247 1500.58L548.841 1528.23L581.439 1531.41C582.756 1531.54 583.762 1532.66 583.762 1533.99C583.762 1535.32 582.757 1536.43 581.439 1536.57L581.454 1536.59Z"""),
            )
        ).also { _send = it }

    // `File` and `Notifications` used to sit here. Both were removed: they were
    // the only two of this icon set with zero call sites in app/src. The
    // notification *scene* in `OrbitAssetArt` is separate artwork and is still
    // live; what had no consumer was these two vector definitions.
    //
    // Every icon below is extracted lazily and cached, so an unused one costs
    // nothing at runtime -- but it still costs a reader's time, and a set of
    // "available" icons reads as an inventory of features that exist.

    private var _pin: ImageVector? = null
    val Pin: ImageVector
        get() = _pin ?: discordIcon(
            name = "Pin",
            viewportSize = 24f,
            paths = listOf(
                CollectionPath(
                    data = "M19.293 6.293l-1.586-1.586a1 1 0 0 0-1.414 0L14.5 6.5l-2.793-2.793a1 1 0 0 0-1.414 0l-1.5 1.5a1 1 0 0 0 0 1.414L10.586 8.414 4.293 14.707a1 1 0 0 0 0 1.414l1.586 1.586a1 1 0 0 0 1.414 0l6.293-6.293 1.793 1.793a1 1 0 0 0 1.414 0l1.5-1.5a1 1 0 0 0 0-1.414L14.5 8.914l1.793-1.793a1 1 0 0 0 0-1.414l1.414-1.414a1 1 0 0 0 0-1.414zM3 21l4-1-3-3-1 4z"
                ),
            )
        ).also { _pin = it }

    private var _members: ImageVector? = null
    val Members: ImageVector
        get() = _members ?: discordIcon(
            name = "Members",
            viewportSize = 24f,
            paths = listOf(
                CollectionPath(
                    data = "M14 8a4 4 0 1 0-8 0 4 4 0 0 0 8 0zm-2 0a2 2 0 1 1-4 0 2 2 0 0 1 4 0zm7 12v-1.5c0-2.485-2.015-4.5-4.5-4.5h-9C3.015 14 1 16.015 1 18.5V20h18zm-2-1.5H3c.245-1.42 1.48-2.5 3-2.5h8c1.52 0 2.755 1.08 3 2.5z"
                ),
            )
        ).also { _members = it }

    private var _chevronLeft: ImageVector? = null
    val ChevronLeft: ImageVector
        get() = _chevronLeft ?: collectionIcon(
            name = "ChevronLeft",
            originX = 0f,
            originY = 0f,
            paths = listOf(
                CollectionPath("""M51.4 24.7L46.7 20L26.7 40L46.7 60L51.4 55.3L36.1 40Z""")
            )
        ).also { _chevronLeft = it }

    private var _chevronRight: ImageVector? = null
    val ChevronRight: ImageVector
        get() = _chevronRight ?: collectionIcon(
            name = "ChevronRight",
            originX = 0f,
            originY = 0f,
            paths = listOf(
                CollectionPath("""M28.6 24.7L33.3 20L53.3 40L33.3 60L28.6 55.3L43.9 40Z""")
            )
        ).also { _chevronRight = it }
}

private data class CollectionPath(
    val data: String,
    val fillType: PathFillType = PathFillType.NonZero
)

private fun discordIcon(
    name: String,
    viewportSize: Float = 24f,
    paths: List<CollectionPath>
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = viewportSize,
    viewportHeight = viewportSize
).apply {
    paths.forEach { path ->
        addPath(
            pathData = PathParser().parsePathString(path.data).toNodes(),
            pathFillType = path.fillType,
            fill = SolidColor(Color.Black)
        )
    }
}.build()

private fun collectionIcon(
    name: String,
    originX: Float,
    originY: Float,
    paths: List<CollectionPath>
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 80f,
    viewportHeight = 80f
).apply {
    group(translationX = -originX, translationY = -originY) {
        paths.forEach { path ->
            addPath(
                pathData = PathParser().parsePathString(path.data).toNodes(),
                pathFillType = path.fillType,
                fill = SolidColor(Color.Black)
            )
        }
    }
}.build()
