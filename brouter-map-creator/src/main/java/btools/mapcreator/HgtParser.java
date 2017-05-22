package btools.mapcreator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;

public class HgtParser {
    private static final int RASTER = 1201;
    private final File hgtFile;
    private final int xllcorner;
    private final int yllcorner;

    public HgtParser(File hgtFile, int xllcorner, int yllcorner) {
        this.hgtFile = hgtFile;
        this.xllcorner = xllcorner;
        this.yllcorner = yllcorner;
    }

    public SrtmRaster parse() throws IOException {
        SrtmRaster srtmRaster = new SrtmRaster();
        srtmRaster.ncols = RASTER;
        srtmRaster.nrows = RASTER;
        srtmRaster.xllcorner = xllcorner;
        srtmRaster.yllcorner = yllcorner;

        short[] elevations = new short[RASTER * RASTER];

        final ByteBuffer bb = ByteBuffer.allocateDirect(RASTER * RASTER * 2);
        bb.order(ByteOrder.BIG_ENDIAN);

        try (FileChannel channel = new FileInputStream(hgtFile).getChannel()) {
            int readBytes = channel.read(bb);

            int elevationIndex = 0;
            while ((readBytes != -1) && (readBytes != 0)) {
                assert elevationIndex + readBytes / 2 <= RASTER * RASTER : "Expected that hgt file " + hgtFile.getName() + " format contains short array of exact size 1201 * 1201. This file is bigger. " + (elevationIndex + readBytes / 2) + ". Using HGT file with better resolution?";
                bb.flip();
                while (bb.hasRemaining()) {
                    elevations[elevationIndex++] = bb.getShort();
                }
                bb.clear();
                readBytes = channel.read(bb);
            }

            //TODO btools/mapcreator/SrtmRaster.java:54 multiplies by 4
            assert elevationIndex == RASTER * RASTER : "Expected that hgt file " + hgtFile.getName() + " format contains short array of exact size 1201 * 1201. This file is smaller. Only " + elevationIndex + " elements. Is it corrupted?";

            srtmRaster.eval_array = elevations;
            srtmRaster.noDataValue = Short.MIN_VALUE;
            srtmRaster.xcoveradge = 1 * 1000000;
            srtmRaster.xraster = RASTER - 1;
            srtmRaster.ycoveradge = 1 * 1000000;
            srtmRaster.yraster = RASTER - 1;
        } catch (ClosedByInterruptException e) {
            System.out.printf("Reading %s was interrupted%n", hgtFile.getName());
        }

        return srtmRaster;
    }
}
