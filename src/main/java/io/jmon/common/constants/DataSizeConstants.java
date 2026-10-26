package io.jmon.common.constants;

/**
 * Byte-unit conversion factors shared by collectors, analyzers and the
 * renderer so reported magnitudes stay consistent end to end. Value
 * derived from the IEC binary prefix; expressed as double because every
 * consumer formats fractional output.
 */
public final class DataSizeConstants {

    private DataSizeConstants() {
    }

    /** Bytes per kibibyte. */
    public static final double KIB_BYTES = 1024.0;

    /** Bytes per mebibyte (kibibyte squared). */
    public static final double MIB_BYTES = 1024.0 * 1024.0;
}
