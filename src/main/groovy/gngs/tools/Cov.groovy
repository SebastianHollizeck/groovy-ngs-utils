package gngs.tools

import gngs.*
import graxxia.IntegerStats
import graxxia.Matrix
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Log
import groovyx.gpars.actor.Actor
import groovyx.gpars.actor.Actors
import htsjdk.samtools.BAMIndex
import htsjdk.samtools.BAMIndexMetaData
import htsjdk.samtools.SAMRecord
import htsjdk.samtools.SamReader
import htsjdk.samtools.SamReader.Indexing

@CompileStatic
class ReadSpans {
    int [][] intervals
    int count
    
    String toString() { "$count read spans from ${intervals[0][0]} to ${intervals[count-1][1]}"}
}

@Log
@CompileStatic
class GapIntersector extends RegulatingActor<CoverageBlock> {
    
    Regions targetRegions
    
    RegulatingActor<CoverageBlock> downstream

    @Override
    public void process(CoverageBlock block) {
        if(targetRegions.overlaps(block))
            downstream.sendTo(block)
    }
}

/**
 * SingleCov is a counterpoint to MultiCov, offering a subset of its functionality
 * for single samples at higher performance.
 * 
 * SingleCov produces per-base coverage and overall coverage statistics for a set 
 * of target regions on a BAM file. It writes a sample_summary file in the same
 * format that GATKDepthOfCoverage does, and per-base coverage in plain TSV,
 * GZIP or BGZIPPED format (auto-detected from the file name).
 * 
 * The main reason SingleCov is faster is because it trades memory for speed; it 
 * reads a whole chromosome of read spans at a time into an int-array and 
 * computes coverage depth from their overlaps in a block. This avoids all 
 * latency in reading, etc. This makes it 2-3 times faster than MultiCov for
 * single samples. However it takes several GB in memory to store the full
 * matrix of read spans and coverage values for each chromosome in memory,
 * making it a non-viable approach for multiple samples.
 * 
 * @author Simon Sadedin
 */
@Log
class Cov extends ToolBase {

    Regions scanRegions
    
    List<String> contigs
    
    Map<String,Integer> contigSizes
    
    IntegerStats covStats = new IntegerStats(1000)

    boolean allowDuplicates = false
    
    SAM bam 
    
    Writer out

    Writer downsampledOut

    Writer gaps
    
    int minimumMappingQuality = 1
    
    final static byte COVERAGE_MODE_NO_COUNT_OVERLAPS = 0

    final static byte COVERAGE_MODE_HALF_COUNT_OVERLAPS = 1
    
    byte coverageOverlapMode = COVERAGE_MODE_NO_COUNT_OVERLAPS;
    
    int gapThreshold = 20
    
    GapAnnotator gapAnnotator
    CoverageGaps gapCalculator
    
    Matrix kmerFactors
    
    double [] sampleKmerFactors
  
    @Override
    public void run() {
        
        if(!opts.arguments()) 
            throw new IllegalArgumentException("Please provide a BAM file to analyse")
            
        Regions targetRegions = new BED(opts.L).load()
        
        this.scanRegions = targetRegions.reduce().toSorted(new NumericRegionComparator())    
        
        this.contigs = scanRegions.index*.key
        
        if(opts.reference){
            this.bam = new SAM(opts.arguments()[0], opts.reference)
        }else{
            if(opts.arguments()[0].endsWith(".cram")){
                log.severe "CRAM file specified (${opts.arguments()[0]}) but no reference specified"
                System.exit(1)
            }else{
                this.bam = new SAM(opts.arguments()[0])
            }
        }
        
        this.contigSizes = bam.contigs
        
        if(opts.gt) 
            this.gapThreshold = Integer.parseInt(opts.gt)

        if(opts.gaps) {
            log.info "Will calculate gaps based on coverage depth threshold of $gapThreshold"
        }
        
        if(opts.do) {
            this.downsampledOut = Utils.writer(opts.do)
        }

        initOverlapMode()
        
        if(opts.minMQ)
            this.minimumMappingQuality = opts.minMQ.toInteger()

        if(opts.allowDuplicates)
            this.allowDuplicates = opts.allowDuplicates
            
        log.info "Analysing ${Utils.humanBp(scanRegions.size())} from ${opts.arguments()[0]}"
        log.info "Mapping quality threshold = $minimumMappingQuality"
        log.info "Duplicates allowed = $allowDuplicates"
        
        if(opts.kmer) {
            this.initKmerProfile()
        }
        
        openStreams()
        
        if(opts.gaps) {
            initGapCalculator()
        }
        
        int downsampleFactor = opts.downsampleFactor ?: 0

        final boolean createRegionStats = opts['intervalsummary'] != false
        
        RegulatingActor writerActor = RegulatingActor.actor { contig, covs ->
            writeCoverage(contig, covs, downsampleFactor, createRegionStats)
        }
        writerActor.start()
        
        RegulatingActor countActor = RegulatingActor.actor { contig, spans ->
            def covs = countCoverage(contig,spans)
            writerActor.sendTo([contig,covs])
        }
        countActor.start()
        
        try {
            for(String contig in contigs) {
                log.info "Process $contig"
                ReadSpans spans = readContig(contig)
                countActor.sendTo([contig, spans])
            }

            countActor.sendStop()
            countActor.join()

            writerActor.sendStop()
            writerActor.join()
        }
        finally {
            out?.close()
            if(downsampledOut)
                downsampledOut.close()
        }

        if(gaps) {
            
            if(opts.gaptarget) {
                gapCalculator.gapProcessor.sendStop()
                gapCalculator.gapProcessor.join()
            }
            
            gapAnnotator.sendStop()
            gapAnnotator.join()
        }
        
        writeGaps()
        
        log.info "Coverage statistics: $covStats"
        
        if(opts.samplesummary) {
            writeSampleSummary(opts.samplesummary, covStats)
        }
        if(opts.covo) {
            printCoverageJs(covStats, opts.covo)
        }
        
        if(opts.intervalsummary) {
            printIntervalStats(opts.intervalsummary)
        }
    }
    
   
    private void initKmerProfile() {
        
       log.info "Calculating kmer weightings based on kmer profile: $opts.kmer"
       Matrix kmerCounts = Matrix.load(opts.kmer) 
       Matrix norm = kmerCounts.normaliseRows().normaliseColumns()
       this.kmerFactors = norm.transform { double value ->
           if(value != 0) {
               return 1.0d / value
           }
           else {
               return 1.0d // multiplier will be 1.0d when factor cannot be calculated
           }
       }.fillna(1.0)
       
       this.sampleKmerFactors = this.kmerFactors.grep { sample == bam.samples[0] }.rawData[0]
       this.kmerFactors.@displayColumns=10
       this.kmerFactors.@displayPrecision=2
       
       if(opts.okmer) {
           this.kmerFactors.save(opts.okmer, r:true)
       }

       log.info "Calculated kmer profile matrix:\n" + this.kmerFactors
    }

    private initGapCalculator() {
        gapAnnotator = new GapAnnotator(new RefGenes((String)opts['refgene']))
        gapAnnotator.start()
        gapCalculator = new CoverageGaps()
        gapCalculator.threshold = this.gapThreshold

        if(opts.gaptarget) {
            Regions gapRegions = new BED(opts.gaptarget).load()
            gapCalculator.gapProcessor = new GapIntersector(downstream:gapAnnotator, targetRegions:gapRegions)
            gapCalculator.gapProcessor.start()
        }
        else {
            gapCalculator.gapProcessor = gapAnnotator
        }
    }
    
    private void writeGaps() {
        if(!gaps)
            return
        
        Regions gapTargets
        if(opts.gaptarget) {
            gapTargets = new BED(opts.gaptarget).load()
            log.info "Filtering to gaps overlapping gap targets: $opts.gaptarget (${Utils.humanBp(gapTargets.size())})"
        }
        this.gaps.write((Gaps.DEFAULT_COLUMNS + GapAnnotator.ANNOTATION_OUTPUT_COLUMNS).join(',') + '\n')
        
        Gaps gapWriter = new Gaps(new CliOptions(overrides:[L:opts.L, r:opts.refgene, csv:true]))
        gapWriter.gapWriter = null
        for(CoverageBlock block in gapCalculator.blocks) {
            if(gapTargets && !gapTargets.overlaps(block))
                continue
                
            Region adjBlock = new Region(block.chr, block.start-1, block.end)

            List<IntRange> ixs = gapTargets ? gapTargets.intersect(adjBlock) : [adjBlock.range]
            ixs.each { ix ->
                block.start = ix.from
                block.end = ix.to
                gapWriter.writeGapBlock(block, this.gaps)
            }
        }
        gaps.close()
    }

    private openStreams() {
        if(opts.o && opts.o != '/dev/null')
            out = new AsciiWriter(Utils.outputStream(opts.o,10*1024*1024), 5*1024*1024)

        if(opts.gaps) {
            if(!opts.refgene)
                throw new IllegalArgumentException("If -gaps is specified then -refgene is required")

            gaps = new AsciiWriter(Utils.outputStream(opts.gaps,10*1024*1024), 5*1024*1024)
        }
    }

    private initOverlapMode() {
        String overlapModeValue = opts.om ?:'none'
        if(overlapModeValue == 'none') {
            this.coverageOverlapMode = COVERAGE_MODE_NO_COUNT_OVERLAPS
        }
        else
        if(overlapModeValue == 'half') {
            log.info "Calculating coverage in legacy half-overlap mode"
            this.coverageOverlapMode = COVERAGE_MODE_HALF_COUNT_OVERLAPS
        }
        else
            throw new IllegalArgumentException('Overlap mode ' + opts.om + ' is not a valid value')
    }
    
    private final void writeDownsampled(final String contig, final int pos, IntegerStats stats) {
        downsampledOut.write(contig)
        downsampledOut.write('\t')
        downsampledOut.write(String.valueOf(pos))
        downsampledOut.write('\t')
        downsampledOut.write(String.valueOf(stats.mean))
        downsampledOut.write('\n')
    }
    
    Map<String,IntegerStats> allRegionStats = [:]
   
    @CompileStatic
    private final void writeCoverage(String contig, short [] covs, final int downsampleFactor, final boolean createRegionStats) {
        final Regions contigRegions = scanRegions.getContigRegions(contig)

        log.info "Write ${Utils.human(contigRegions.size())} coverage values for contig: $contig"
        
        IntegerStats downsampleStats = downsampleFactor>0 ? new IntegerStats(1000) : null
        for(Region r in contigRegions) {
            IntegerStats regionStats = createRegionStats ? new IntegerStats(1000)  : null
            downsampleStats?.clear()
            final int start = r.from
            final int end = r.to
            final int zero = 0
            final int covLen = covs.size()
            final int downsamplePoint = (int)(downsampleFactor / 2i)
            int offset = 0
            for(int pos = start; pos < end; ++pos) {

                int cov = pos< covLen ? Math.max(Math.min(1000,covs[pos]),zero) : 0
                if(gaps != null) 
                    gapCalculator.processLine(r.chr, start, pos, cov, '')

                if(downsampleFactor>0) {
                    if(offset % downsampleFactor == downsamplePoint) {
                        writeDownsampled(contig,pos,downsampleStats)
                    }
                    else {
                        downsampleStats.addValue(cov)
                    }
                }

                if(createRegionStats)
                    regionStats.addIntValue(cov)

                covStats.addIntValue(cov)
                if(!out.is(null)) {
                    out.write(contig)
                    out.write('\t')
                    out.write(String.valueOf(pos))
                    out.write('\t')
                    out.write(String.valueOf(cov))
                    out.write('\n')
                }
                ++offset
            }
            
            if(createRegionStats)
                allRegionStats[r.toString()] = regionStats
            
            if(downsampleFactor>0 && offset<downsamplePoint) {
                writeDownsampled(contig,start+offset,downsampleStats)
            }
        }
    }
    
    @CompileStatic
    short [] countCoverage(final String contig, final ReadSpans spans) {
        
        if(spans.count == 0)
            return [] as short[];

        final boolean adjustForKmerProfile = (this.sampleKmerFactors != null)
        
        int covSize = spans.intervals[spans.count-1][1]
        
        log.info "Allocate ${Utils.human(covSize*2)} bytes for coverage values for $contig"

        short [] covs =  new short[covSize+1]
        
        final ot = new gngs.OverlapTracker()
    
        int pos = 0
        
        final int[][] intervals = spans.intervals
        
        final int len = spans.count
    
        for(int i=0; i<len; ++i) {
    
            final int start = intervals[i][0]
            while(pos < start) {
                ot.removeNonOverlaps(pos)
                if(adjustForKmerProfile) {
                    double cov = 0.0d
                    final int n = ot.size()
                    for(int j=0; j<n; ++j) {
                        // each kmer factor represents how much "one read" should count
                        int kmerIndex = ot.reads[j][2]
                        cov += this.sampleKmerFactors[kmerIndex]
                    }
                    covs[pos] = (short)cov
                }
                else {
                    covs[pos] = (short)ot.size()
                }
                ++pos
            }
    
            ot.add(intervals[i])
        }
        log.info "Computed ${covs.size()} coverage values"
        return covs
    }
    
    @CompileStatic
    ReadSpans readContig(final String contig) {
        
        int i = 0
        int seqIndex
        
        int recordCount = (int)bam.withReader { SamReader r ->
            
            seqIndex = r.fileHeader.sequenceDictionary.sequences.find { it.sequenceName == contig }.sequenceIndex

            BAMIndexMetaData meta = ((Indexing)r).index.getMetaData(seqIndex)
            
            meta.getAlignedRecordCount()
        }
        // if we have a cram, aligned read numbers arent stored so we assign a high enough initial capacity so we almost
        // are using an array
        if(recordCount ==0) {
            // we estimate 200M reads as a reasonable higher of a "normal" number of reads we might
            // observe in practice on a whole genome
            recordCount = 200000000
        }

        ArrayList<int[]>  intervals = new ArrayList<int[]>(recordCount)

        Region region = new Region("$contig:0-${contigSizes[contig]}")
        bam.withIterator(region) {
            final byte com = coverageOverlapMode
            final boolean addKmerinfo = (this.kmerFactors != null)

            while(it.hasNext()) {
                SAMRecord r = it.next()
                if(r.getReadUnmappedFlag())
                    continue
                if(r.secondaryOrSupplementary)
                    continue
                if(r.mappingQuality<minimumMappingQuality)
                    continue
                if(r.getDuplicateReadFlag() && ! allowDuplicates)
                    continue
                    
                final int mateStart = r.getMateAlignmentStart();
                int alignmentEnd = r.alignmentEnd
                final int alignmentStart = r.alignmentStart

                if(!r.readPairedFlag) {
                    // Do nothing - no need to take overlaps into account
                } 
                else
                if(com == COVERAGE_MODE_NO_COUNT_OVERLAPS) {
                    // Don't double count if reads overlap
                    if(r.getFirstOfPairFlag() && mateStart == alignmentStart) { 
                        // Note this logic is special: for the special case where the reads exactly overlap,
                        // there is no reliable choice which read is "first", so we can't decide which one
                        // to clip based on start position. That is why for the special case that they exactly
                        // overlap, we arbitrarily choose to exclude the first-of-pair completely from the coverage
                        // count and keep the whole second read
                        continue
                    }
                    else
                    if(mateStart > alignmentStart && mateStart <= alignmentEnd && r.mateReferenceIndex == seqIndex && !r.mateUnmappedFlag) 
                        alignmentEnd = mateStart
                }
                else { // COVERAGE_MODE_HALF_COUNT_OVERLAPS
                    // This is a legacy mode that emulates behaviour of our previous coverage
                    // depth calculations. It is suboptimal because it fails to compensate for
                    // read overlap when the R1 is not first of pair
                    if(r.getFirstOfPairFlag() && mateStart >= alignmentStart && mateStart <= alignmentEnd && r.mateReferenceIndex == seqIndex && !r.mateUnmappedFlag) {
                        alignmentEnd = mateStart - 1;
                    }
                }

                if(addKmerinfo) {
                    intervals.add([alignmentStart, alignmentEnd, ShearingKmerCounter.computeKmer(r)] as int[])
                }
                else {
                    intervals.add([alignmentStart, alignmentEnd] as int[])
                }
                ++i
            }
        }
        log.info "Scanned ${Utils.human(i)} reads from $bam.samFile"
        return new ReadSpans(intervals:intervals.toArray(new int[0][]), count:i)
    }
    
   
    void writeSampleSummary(String outputFile, IntegerStats stats) {

        List<String> headers = ['Median Coverage', 'Mean Coverage', 'perc_bases_above_1', 'perc_bases_above_5', 'perc_bases_above_10', 'perc_bases_above_20', 'perc_bases_above_50']

        new File(outputFile).withWriter { w ->
            w.println(headers.join("\t"))
            w.println([
                stats.median,
                Utils.humanNumberFormat.format(stats.mean),
                Utils.humanNumberFormat.format(100*stats.fractionAbove(1)),
                Utils.humanNumberFormat.format(100*stats.fractionAbove(5)),
                Utils.humanNumberFormat.format(100*stats.fractionAbove(10)),
                Utils.humanNumberFormat.format(100*stats.fractionAbove(20)),
                Utils.humanNumberFormat.format(100*stats.fractionAbove(50)),
            ].join('\t'))
        }
        log.info "Wrote sample summary for ${bam.samples[0]} to $outputFile"
    }
    
    private void printCoverageJs(IntegerStats stats, String fileName) {
        
        Map statsJson = [ 
            means: [ 
                [ bam.samples[0], stats.mean ]
            ].collectEntries(),
                
            medians: [
                [ bam.samples[0], stats.median]
            ].collectEntries(),
        ]
        
        new File(fileName).withWriter { w ->
            w.println('covs = // NOJSON\n' + JsonOutput.prettyPrint(JsonOutput.toJson(statsJson)))
        }
        log.info "Wrote coverage stats to $fileName"
    }

    private void printIntervalStats(File intervalStatsFile) {
        intervalStatsFile.withWriter { w -> 
            // Print the regions
            w.write(
                ['sample', *allRegionStats*.key].join("\t")
            )
            w.write('\n')
            
            def safeMeans = allRegionStats*.value*.mean.collect { Double.isNaN(it) ? 0d : it }
            w.write(
                [this.bam.samples[0], *safeMeans].join("\t")
            )
        }
        log.info "Wrote interval summary to $intervalStatsFile"
    }
    
    static void main(String[] args) {
        cli('Cov [-o <per-base-output>] -L <target regions> <bam file>', 'Fast per-base coverage calculations with statistics output', args) {
            o 'Output file to write to', args:1, required: false
            'do' 'Output file for downsampled output', args:1, required:false, longOpt: 'downsampleOutput', type: File
            'df' 'Factor to downsample by', args:1, required:false, longOpt: 'downsampleFactor', type: Integer
            minMQ 'Minimum mapping quality (1)', args:1, required: false
            samplesummary 'File to write coverage statistics to in tab separated format', args:1, required: false
            covo 'File to write coverage statistics in js format to', args:1, required: false
            gaps 'File to write annotated gaps to', args:1, required: false
            gaptarget 'Regions over which to report gaps', args:1, required: false
            gt 'Gap threshold - coverage level below which a region is considered a gap', args:1, required: false
            refgene 'Refgene database for annotating gaps (required if -gaps specified)', args:1, required: false
            kmer 'Use the given file of kmer counts to normalise based on kmer-prefix-bias', args:1, required: false
            okmer 'Save computed normalised kmer profile', args:1, required: false
            om 'Overlap mode whether to count overlapping read fragments - one of none,half (default=none)', longOpt:'overlap-mode', args: 1
            intervalsummary 'File to write interval statistics to', args:1, type: File, required: false
            'L' 'Regions over which to report coverage depth', args:1, required: true, longOpt: 'target'
            reference 'Reference to use when decoding crams', args:1, required: false, type:File
            a 'Flag to not discard reads which are considered duplicates', longOpt: 'allowDuplicates', args:0, required: false, type:boolean
        }
    }
    
    
}
