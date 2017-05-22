package btools.mapcreator;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import btools.util.CompactLongSet;
import btools.util.DiffCoderDataOutputStream;
import btools.util.FrozenLongSet;

/**
 * PosUnifier does 3 steps in map-processing:
 *
 * - unify positions - add srtm elevation data - make a bordernodes file
 * containing net data from the bordernids-file just containing ids
 *
 * @author ab
 */
public class PosUnifier extends MapCreatorBase {
    private DiffCoderDataOutputStream nodesOutStream;
    private DiffCoderDataOutputStream borderNodesOut;
    private File nodeTilesOut;
    private CompactLongSet positionSet;

    private Map<String, Future<SrtmRaster>> srtmmap = new HashMap<>();
    private String srtmdir;

    private CompactLongSet borderNids;
    private long noElevationCount;
    private long nodeCount;

    public static void main(String[] args) throws Exception {
        System.out.println("*** PosUnifier: Unify position values and enhance elevation");
        if (args.length != 5) {
            System.out.println("usage: java PosUnifier <node-tiles-in> <node-tiles-out> <bordernids-in> <bordernodes-out> <strm-data-dir>");
            return;
        }
        new PosUnifier().process(new File(args[0]), new File(args[1]), new File(args[2]), new File(args[3]), args[4]);
    }

    public void process(File nodeTilesIn, File nodeTilesOut, File bordernidsinfile, File bordernodesoutfile, String srtmdir) throws Exception {
        this.nodeTilesOut = nodeTilesOut;
        this.srtmdir = srtmdir;

        // read border nids set
        DataInputStream dis = createInStream(bordernidsinfile);
        borderNids = new CompactLongSet();
        try {
            for (; ; ) {
                long nid = readId(dis);
                if (!borderNids.contains(nid))
                    borderNids.fastAdd(nid);
            }
        } catch (EOFException eof) {
            dis.close();
        }
        borderNids = new FrozenLongSet(borderNids);

        // process all files
        borderNodesOut = createOutStream(bordernodesoutfile);
        new NodeIterator(this, true).processDir(nodeTilesIn, ".n5d");
        borderNodesOut.close();
    }

    @Override
    public void nodeFileStart(File nodefile) throws Exception {
        resetSrtm();
        prefetchSrtm(nodefile.getName());

        nodesOutStream = createOutStream(fileFromTemplate(nodefile, nodeTilesOut, "u5d"));

        positionSet = new CompactLongSet();

        noElevationCount = 0;
        nodeCount = 0;
    }

    @Override
    public void nextNode(NodeData n) throws Exception {
        SrtmRaster srtm = srtmForNode(n.ilon, n.ilat);
        n.selev = srtm == null ? Short.MIN_VALUE : srtm.getElevation(n.ilon, n.ilat);

        if (n.selev == Short.MIN_VALUE) {
//            System.out.println("Examine node with no elevation " + n.nid);
            noElevationCount++;
        }

        findUniquePos(n);

        n.writeTo(nodesOutStream);
        if (borderNids.contains(n.nid)) {
            n.writeTo(borderNodesOut);
        }
        nodeCount++;
    }

    @Override
    public void nodeFileEnd(File nodeFile) throws Exception {
        nodesOutStream.close();
        System.out.printf("%d of %d do not have elevation (%s %%)%n", noElevationCount, nodeCount, 100.0 * noElevationCount / nodeCount);
    }

    private void findUniquePos(NodeData n) {
        // fix the position for uniqueness
        int lonmod = n.ilon % 1000000;
        int londelta = lonmod < 500000 ? 1 : -1;
        int latmod = n.ilat % 1000000;
        int latdelta = latmod < 500000 ? 1 : -1;
        for (int latsteps = 0; latsteps < 100; latsteps++) {
            for (int lonsteps = 0; lonsteps <= latsteps; lonsteps++) {
                int lon = n.ilon + lonsteps * londelta;
                int lat = n.ilat + latsteps * latdelta;
                long pid = ((long) lon) << 32 | lat; // id from position
                if (!positionSet.contains(pid)) {
                    positionSet.fastAdd(pid);
                    n.ilon = lon;
                    n.ilat = lat;
                    return;
                }
            }
        }
        System.out.println("*** WARNING: cannot unify position for: " + n.ilon + " " + n.ilat);
    }

    /**
     * get the srtm data set for a position srtm coords are
     * srtm_<srtmLon>_<srtmLat> where srtmLon = 180 + lon, srtmLat = 60 - lat
     */
    SrtmRaster srtmForNode(int ilon, int ilat) throws Exception {
        int lon = ilon / 1000000 - 180;
        int lat = ilat / 1000000 - 90;
        String hgtFileName = getHgtFileName(lon, lat);
        final Future<SrtmRaster> srtmRasterFuture = getSrtmRasterCompletedFuture(hgtFileName, ilon, ilat);
        try {
            return srtmRasterFuture.get();
        } catch (CancellationException e) {
            System.err.println(hgtFileName + " closed before read!");
            throw e;
        }
    }

    private Future<SrtmRaster> getSrtmRasterCompletedFuture(String hgtFileName, int lon, int lat) throws InterruptedException, ExecutionException {
        final Future<SrtmRaster> srtmRasterFuture = srtmmap.get(hgtFileName);
        if (srtmRasterFuture == null) {
            return getSrtmRasterFuture(hgtFileName, lon, lat);
        }
        if (!srtmRasterFuture.isDone()) {
            if (srtmRasterFuture.cancel(false)) {
//                System.out.println("Prefetch did not worked " + hgtFileName);
                return getSrtmRasterFuture(hgtFileName, lon, lat);
            } else {
//                final long start = System.currentTimeMillis();
                srtmRasterFuture.get();
//                final Duration duration = Duration.ofMillis(System.currentTimeMillis() - start);
//                System.out.println("Waited for elevation " + hgtFileName + duration.toString());
            }
        }
        return srtmRasterFuture;
    }

    private Future<SrtmRaster> getSrtmRasterFuture(String hgtFileName, int lon, int lat) {
//        final long start = System.currentTimeMillis();
        final CompletableFuture<SrtmRaster> completedFuture = CompletableFuture.completedFuture(
                readSrtm(hgtFileName,
                        lon / 1000000 * 1000000,
                        lat /  1000000 *1000000,
                        this)
        );
//        final Duration duration = Duration.ofMillis(System.currentTimeMillis() - start);
//        System.out.println("Waited for elevation " + hgtFileName + duration.toString());
        srtmmap.put(hgtFileName, completedFuture);
        return completedFuture;
    }

    private String getHgtFileName(int lon, int lat) {
        String formatedLat = lat >= 0 ? String.format("N%02d", lat) : String.format("S%02d", -lat);
        String formatedLon = lon >= 0 ? String.format("E%03d", lon) : String.format("W%03d", -lon);
        return String.format(String.format("%s%s.hgt", formatedLat, formatedLon));
    }

    private SrtmRaster readSrtm(String hgtFileName, int lon, int lat, PosUnifier posUnifier) {
        File hgtFile = new File(new File(srtmdir), hgtFileName);
        if (!hgtFile.exists()) {
            return null;
        }
        try {
//            final long start = System.currentTimeMillis();
            final SrtmRaster srtmRaster = new HgtParser(hgtFile, lon, lat).parse();
//            final Duration duration = Duration.ofMillis(System.currentTimeMillis() - start);
//            System.out.println("Parsing hgt file " + hgtFileName + " took: " + duration.toString());
            return srtmRaster;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }


    private void resetSrtm() {
        srtmmap.values().forEach(future -> future.cancel(true));
        srtmmap = new HashMap<>();
    }

    private void prefetchSrtm(String name) {
        final Pattern pattern = Pattern.compile("(?<longitudeType>[EW])(?<longitude>\\d+)_(?<latitudeType>[NS])(?<latitude>\\d+).n5d");
        final Matcher matcher = pattern.matcher(name);
        final boolean matches = matcher.matches();
        assert matches : "File format changed. Unable to extract lon/lat from " + name;
        final int latStart = Integer.valueOf(matcher.group("latitude")) * ("N".equals(matcher.group("latitudeType")) ? 1 : -1);
        final int lonStart = Integer.valueOf(matcher.group("longitude")) * ("E".equals(matcher.group("longitudeType")) ? 1 : -1);

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        for (int lat = latStart; lat < latStart + 5; lat++) {
            for (int lon = lonStart; lon < lonStart + 5; lon++) {
                final String hgtFileName = getHgtFileName(lon, lat);
                int finalLon = (lon + 180) * 1000000;
                int finalLat = (lat +  90) * 1000000;
                srtmmap.put(hgtFileName,
                        executorService.submit(
                                () -> readSrtm(hgtFileName, finalLon, finalLat, this)
                        )
                );
            }
        }
    }

}
