package edu.gsu.algorithm.util;

import edu.gsu.algorithm.SNVIlluminaMethod;
import edu.gsu.model.IlluminaSNVSample;
import edu.gsu.model.SNVStructure;
import edu.gsu.start.Start;
import edu.gsu.util.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static edu.gsu.algorithm.AbstractSNV.al;
import static edu.gsu.algorithm.SNVIlluminaMethod.minorCount;
import static edu.gsu.util.Utils.humanReadableSI;

public class FindIlluminaEdgesParallelTask implements Callable<List<FindIlluminaEdgesParallelTask.EdgeSummary>> {
    private final SNVStructure struct;
    private final int allele;
    private final SNVIlluminaMethod method;
    private final IlluminaSNVSample sample;
    private final int[][] commonReads;

    private final boolean ignoreDeletion;

    public FindIlluminaEdgesParallelTask(SNVStructure struct, int allele, SNVIlluminaMethod method, IlluminaSNVSample sample, int[][] commonReads, boolean ignoreDeletion) {
        this.struct = struct;
        this.allele = allele;
        this.method = method;
        this.sample = sample;
        this.commonReads = commonReads;
        this.ignoreDeletion = ignoreDeletion;
    }

    @Override
    public List<EdgeSummary> call() {
        List<EdgeSummary> result = new ArrayList<>();
        int l = struct.rowMinors[allele].length;
        int first = allele / minorCount;
        if (l < method.MIN_O22_THRESHOLD) {
            return result;
        }
        if (first < method.START_POSITION || first > method.END_POSITION) {
            return result;
        }
        int[] hits = method.getHits(struct, struct.rowMinors[allele], sample.referenceLength * minorCount);
        for (int j = 0; j < hits.length; j++) {
            /* tricky formula to balance each task with the same number of positions to calculate
                for i,j it will skip in the following pattern:
                 ****
                  ****
                   ****
                    ****
                     ****
                *     ****
                **     ***
                ***     **
                ****     *
                *****
                So each pair (i,j) calculated exactly once and each task has ~ n/2 pairs to calculate
             */
            if (!((j > allele && j < allele + hits.length / 2) || (j <= allele - hits.length / 2)) || allele == j) {
                continue;
            }
            //skip small amount of hits
            int second = j / minorCount;
            if (second < method.START_POSITION || second > method.END_POSITION) {
                continue;
            }
            long reads = commonReads[first][second];
            if ((first != second && method.hitsFitThreshold(hits[j], reads)
                    && Math.abs(first - second) > method.CLOSE_POSITIONS_THRESHOLD)) {
                //get unsplitted columns, minors, o_kl
                int allele1 = method.getAllele(allele);
                int allele2 = method.getAllele(j);

                char m1 = al.charAt(allele1);
                char m2 = al.charAt(allele2);

                if (ignoreDeletion && (m1 == '-' || m2 == '-')) {
                    continue;
                }
                /*
                 * false 1 means that in actual sample it has another minor or N in given position
                 */
                long o22 = hits[j];
                long o21 = struct.rowMinors[allele].length; //all 2*
                long o12 = struct.rowMinors[j].length; //all *2
                // subtract 2N and false 21 from o21
                o21 = method.calculateO21(o21, second, allele);
                if (o21 == 0) {
                    o12 = method.calculateO12(o12, first, j);
                    long o11 = method.getO11(allele, j, first, second, o22, o21, o12, reads);
//                    if(o11 < method.MIN_O22_THRESHOLD){
//                        continue;
//                    }
                    processZeroO(result, allele, l, first, hits, j, second, reads, m1, m2, o22, o21, o12, o11);
                    continue;
                }
                //subtract N2 and false 12 from o12
                o12 = method.calculateO12(o12, first, j);
                if (o12 == 0) {
                    long o11 = method.getO11(allele, j, first, second, o22, o21, o12, reads);
//                    if(o11 < method.MIN_O22_THRESHOLD){
//                        continue;
//                    }
                    processZeroO(result, allele, l, first, hits, j, second, reads, m1, m2, o22, o21, o12, o11);
                    continue;
                }


                //subtract 1N from reads
                long o11 = method.getO11(allele, j, first, second, o22, o21, o12, reads);
                if (o11 == 0) {
                    long tmp = o11;
                    o11 = o22;
                    o22 = tmp;
                }
//                if (o11 < method.MIN_O22_THRESHOLD){
//                    continue;
//                }
                //start calculate p-value, starting with p
                //double p = struct.rowMinors[i].length/(double)(sample.reads.length - struct.rowN[first].length);
                if (hasO22Edge(o11, o12, o21, o22, reads, 0.0000001, sample.referenceLength, first, second)) {
                    boolean fl = false;
                    if (Start.answer != null) {
                        for (String read : Start.answer.reads) {
                            if (read.charAt(first) == m1 && read.charAt(second) == m2) {
                                fl = true;
                                break;
                            }
                        }
                    }
                    double p = (o12 * o21) / ((double) o11 * reads);

                    method.log(String.format("%d %d %c %c mi1=%s\tma1=%s\tmi2=%s\tma2=%s\tpO=%f\tr=%d\t%d\t%d\t%d\t%d\t%b\t%f",
                            first, second, m1, m2, humanReadableSI(l), humanReadableSI(struct.majorsInRow[first]),
                            humanReadableSI(struct.rowMinors[j].length), humanReadableSI(struct.majorsInRow[second]),

                            Utils.binomialOneMinusPvalue((int) o22, p, (int) reads),
                            reads, o11, o12, o21, o22, fl,
                            (o22 / (method.MAX_READ_ERROR * o11 + (1 - method.MAX_READ_ERROR) * (o12 + o21)))
                            )
                    );
                    double freq = o22 * 1. / commonReads[first][second];
                    //correlationMap.put(getCorrelationKey(first, second, m1, m2), new CorrelationContainer(o11, o12, o21, o22, reads));
                    result.add(new EdgeSummary(allele, j, o11, o12, o21, o22,
                            Utils.binomialOneMinusPvalue((int) o22, p, (int) reads),
                            freq, fl));
                }
            }
        }
        return result;
    }

    public void processZeroO(List<EdgeSummary> resultList, int i, int l, int first, int[] hits, int j, int second, long reads, char m1, char m2, long o22, long o21, long o12, long o11) {
        if (o22 / (method.MAX_READ_ERROR * o11 + (1 - method.MAX_READ_ERROR) * (o12 + o21)) < method.MAX_READ_ERROR) {
            return;
        }
        boolean fl = false;
        if (Start.answer != null) {
            for (String read : Start.answer.reads) {
                if (read.charAt(first) == m1 && read.charAt(second) == m2) {
                    fl = true;
                    break;
                }
            }
        }
        method.log(String.format("%d %d %c %c mi1=%s\tma1=%s\tmi2=%s\tma2=%s\tpO=%f\tr=%d\t%d\t%d\t%d\t%d\t%b\t%f",
                            first, second, m1, m2, humanReadableSI(l), humanReadableSI(struct.majorsInRow[first]),
                            humanReadableSI(struct.rowMinors[j].length), humanReadableSI(struct.majorsInRow[second]),
                            0.0,
                            reads, o11, o12, o21, o22, fl,
                            (o22 / (method.MAX_READ_ERROR * o11 + (1 - method.MAX_READ_ERROR) * (o12 + o21)))
                            )
                    );
        //correlationMap.put(getCorrelationKey(first, second, m1, m2), new CorrelationContainer(o11, o12, o21, o22, reads));
        double freq = o22 * 1. / commonReads[first][second];
        double p = (o12 * o21) / ((double) o11 * reads);
        resultList.add(new EdgeSummary(i, j, o11, o12, o21, o22, Utils.binomialLogPvalue((int) o22, p, (int) reads), freq, fl));
    }

    public boolean hasO22Edge(long o11, long o12, long o21, long o22, long reads, double adjustment, int referenceLength, int first, int second) {
        double p = (o12 * o21) / ((double) o11 * reads);
        if (p > 1) {
            return false;
        }
        if (p < 1E-12) {
            return true;
        } else {
            double pvalue = Utils.binomialOneMinusPvalue((int) o22, p, (int) reads);

            boolean maxReadErrorExceeded = false;
            // check only for paired reads or single reads with close enough SNPs
            if (!(sample.singleRead && Math.abs(first - second) > 0.8 * method.sampleFragmentLength)) {
                maxReadErrorExceeded = o22 / (method.MAX_READ_ERROR * o11 + (1 - method.MAX_READ_ERROR) * (o12 + o21)) > method.MAX_READ_ERROR;
            }
            return pvalue < adjustment / (referenceLength * 1. * (referenceLength - 1) / 2)
                    || maxReadErrorExceeded;
        }
    }

    public static class EdgeSummary {
        public int i;
        public int j;
        public long o11, o12, o21, o22;
        public double p;
        public double relFreq;
        public boolean trueEdge;
        public double second;

        public EdgeSummary(int i, int j, long o11, long o12, long o21, long o22, double p, double relativeFreq, boolean trueEdge) {
            this.i = i;
            this.j = j;
            this.o11 = o11;
            this.o12 = o12;
            this.o21 = o21;
            this.o22 = o22;
            this.p = p;
            this.relFreq = relativeFreq;
            this.trueEdge = trueEdge;
            this.second = o22 / (0.1 * o11 + (1 - 0.1) * (o12 + o21));
        }

        @Override
        public String toString() {
            return "Edge{" + "i=" + i / 4 +
                    ", j=" + j / 4 +
                    ", o11=" + o11 +
                    ", o12=" + o12 +
                    ", o21=" + o21 +
                    ", o22=" + o22 +
                    ", p=" + p +
                    ", freq=" + relFreq +
                    ", t=" + trueEdge +
                    ", s=" + second +
                    '}';
        }
    }
}
