package btools.mapcreator;

import java.io.*;

public class HgtParser {
    private final File hgtFile;
    private final double xllcorner;
    private final double yllcorner;

    public HgtParser(File hgtFile, double xllcorner, double yllcorner) {
        this.hgtFile = hgtFile;
        this.xllcorner = xllcorner;
        this.yllcorner = yllcorner;
    }

    public SrtmRaster parse() {
        SrtmRaster srtmRaster = new SrtmRaster();
        srtmRaster.ncols = 1201;
        srtmRaster.nrows = 1201;
        srtmRaster.xllcorner=xllcorner;
        srtmRaster.yllcorner=yllcorner;

        short[] elevations = new short[1201 * 1201];

        try {
            InputStream fileInputStream = new BufferedInputStream(new FileInputStream(hgtFile));
            for( int i = 0; i < 1201; i++) {
                for ( int j = 0; j < 1201; j++) {
                    //TODO btools/mapcreator/SrtmRaster.java:54 multiplies by 4
                    elevations[i*1201 +j] = (short)((256 * fileInputStream.read() + fileInputStream.read())/4);
                }
            }
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("File not found.", e);
        } catch (IOException e) {
            throw new IllegalStateException("Hgt file is corrupted", e);
        }
        srtmRaster.eval_array = elevations;
        srtmRaster.noDataValue = Short.MIN_VALUE;
        srtmRaster.cellsize = 1 / 1201.;

        return srtmRaster;
    }
}
