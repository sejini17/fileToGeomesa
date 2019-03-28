package fileToGeomesa;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.DataUtilities;
import org.geotools.data.FeatureWriter;
import org.geotools.data.Transaction;
import org.geotools.factory.Hints;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.filter.identity.FeatureIdImpl;
//import org.locationtech.geomesa.fs.FileSystemDataStoreFactory;
//import org.locationtech.geomesa.fs.storage.interop.PartitionSchemeUtils;
import org.locationtech.geomesa.utils.interop.SimpleFeatureTypes;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableBatchProcessing
public class SimpleJobConfiguration {
	@Autowired
	private JobBuilderFactory jobs;

	@Autowired
	private StepBuilderFactory steps;

	private static final Log logger = LogFactory.getLog(SimpleJobConfiguration.class);
    
	
    @Bean
    public Job simpleJob() {
        return jobs.get("simpleJob")
                .start(stepPrepareDataStore())
                .next(stepCreateSchema())
                .next(stepReadData())
                .next(stepWriteData())
                .build();
    }


    private DataStore datastore = null;
    @Bean
    public Step stepPrepareDataStore() {
//    	String[] args = new String[] {
//    			"--fs.path"
//    			, "hdfs://sjk-centos:8020/test_0327/"
//    			, "--fs.encoding"
//    			, "parquet"
//    	};
        Map<String, String> parameters = new HashMap<>();
        parameters.put("hbase.catalog", "example");

        return steps.get("stepPrepareDataStore")
                .tasklet((contribution, chunkContext) -> {
                	logger.info(">>>>> This is stepPrepareDataStore");

                	logger.info("Loading datastore");

                    // use geotools service loading to get a datastore instance
                    datastore = DataStoreFinder.getDataStore(
                            parameters
//                    		CommandLineDataStore.getDataStoreParams(
//                    				CommandLineDataStore.parseArgs(
//                    						getClass(),
//                    						CommandLineDataStore.createOptions(new FileSystemDataStoreFactory().getParametersInfo()),
//                    						args
//                    						)
//                    				)
                    		);
                    if (datastore == null) {
                        throw new RuntimeException("Could not create data store with provided parameters");
                    }
                    
                    return RepeatStatus.FINISHED;
                })
                .build();
    }
    
    private SimpleFeatureType sft = null;
    @Bean
    public Step stepCreateSchema() {
        return steps.get("stepCreateSchema")
                .tasklet((contribution, chunkContext) -> {
                	logger.info(">>>>> This is stepCreateSchema");

                    sft = getSimpleFeatureType();
                    // For the FSDS we need to modify the SimpleFeatureType to specify the index scheme
                    // GeoMesa의 FS을 사용하는 경우에 추가되는 스키마 정의 코드
//                    PartitionSchemeUtils.addToSft(
//                    		sft,
//                    		PartitionSchemeUtils.apply(sft, "daily,z2-2bit", Collections.emptyMap())
//                    		);
                    
                	logger.info("Creating schema: " + DataUtilities.encodeType(sft));
                	datastore.createSchema(sft);
                    
                    return RepeatStatus.FINISHED;
                })
                .build();
    }
    

    private SimpleFeatureType getSimpleFeatureType() {
    	final String FEATURE_TYPE_NAME = "gdelt-type";
        SimpleFeatureType sft = null;
        // list the attributes that constitute the feature type
        // this is a reduced set of the attributes from GDELT 2.0
        StringBuilder attributes = new StringBuilder();
        attributes.append("GLOBALEVENTID:String,");
        attributes.append("Actor1Name:String,");
        attributes.append("Actor1CountryCode:String,");
        attributes.append("Actor2Name:String,");
        attributes.append("Actor2CountryCode:String,");
        attributes.append("EventCode:String:index=true,"); // marks this attribute for indexing
        attributes.append("NumMentions:Integer,");
        attributes.append("NumSources:Integer,");
        attributes.append("NumArticles:Integer,");
        attributes.append("ActionGeo_Type:Integer,");
        attributes.append("ActionGeo_FullName:String,");
        attributes.append("ActionGeo_CountryCode:String,");
        attributes.append("dtg:Date,");
        attributes.append("*geom:Point:srid=4326"); // the "*" denotes the default geometry (used for indexing)

        // create the simple-feature type - use the GeoMesa 'SimpleFeatureTypes' class for best compatibility
        // may also use geotools DataUtilities or SimpleFeatureTypeBuilder, but some features may not work
        sft = SimpleFeatureTypes.createType(FEATURE_TYPE_NAME, attributes.toString());

        // use the user-data (hints) to specify which date field to use for primary indexing
        // if not specified, the first date attribute (if any) will be used
        // could also use ':default=true' in the attribute specification string
        sft.getUserData().put(SimpleFeatureTypes.DEFAULT_DATE_KEY, "dtg");

        return sft;
    }
    
    private List<SimpleFeature> features = null;
    @Bean
    public Step stepReadData() {
        return steps.get("stepReadData")
                .tasklet((contribution, chunkContext) -> {
                	logger.info(">>>>> This is stepReadData");

                    features = new ArrayList<>();

                    // read the bundled GDELT 2.0 TSV
                    URL input = getClass().getClassLoader().getResource("20180101000000.export.CSV");
                    if (input == null) {
                        throw new RuntimeException("Couldn't load resource 20180101000000.export.CSV");
                    }

                    // date parser corresponding to the CSV format
                    DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);

                    // use a geotools SimpleFeatureBuilder to create our features
                    SimpleFeatureBuilder builder = new SimpleFeatureBuilder(getSimpleFeatureType());

                    // use apache commons-csv to parse the GDELT file
                    try (CSVParser parser = CSVParser.parse(input, StandardCharsets.UTF_8, CSVFormat.TDF)) {
                        for (CSVRecord record : parser) {
                            try {
                                // pull out the fields corresponding to our simple feature attributes
                                builder.set("GLOBALEVENTID", record.get(0));

                                // some dates are converted implicitly, so we can set them as strings
                                // however, the date format here isn't one that is converted, so we parse it into a java.util.Date
                                builder.set("dtg",
                                    Date.from(LocalDate.parse(record.get(1), dateFormat).atStartOfDay(ZoneOffset.UTC).toInstant()));

                                builder.set("Actor1Name", record.get(6));
                                builder.set("Actor1CountryCode", record.get(7));
                                builder.set("Actor2Name", record.get(16));
                                builder.set("Actor2CountryCode", record.get(17));
                                builder.set("EventCode", record.get(26));

                                // we can also explicitly convert to the appropriate type
                                builder.set("NumMentions", Integer.valueOf(record.get(31)));
                                builder.set("NumSources", Integer.valueOf(record.get(32)));
                                builder.set("NumArticles", Integer.valueOf(record.get(33)));

                                builder.set("ActionGeo_Type", record.get(51));
                                builder.set("ActionGeo_FullName", record.get(52));
                                builder.set("ActionGeo_CountryCode", record.get(53));

                                // we can use WKT (well-known-text) to represent geometries
                                // note that we use longitude first ordering
                                double latitude = Double.parseDouble(record.get(56));
                                double longitude = Double.parseDouble(record.get(57));
                                builder.set("geom", "POINT (" + longitude + " " + latitude + ")");

                                // be sure to tell GeoTools explicitly that we want to use the ID we provided
                                builder.featureUserData(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE);

                                // build the feature - this also resets the feature builder for the next entry
                                // use the GLOBALEVENTID as the feature ID
                                SimpleFeature feature = builder.buildFeature(record.get(0));

                                features.add(feature);
                            } catch (Exception e) {
                                logger.debug("Invalid GDELT record: " + e.toString() + " " + record.toString());
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Error reading GDELT data:", e);
                    }
                    features = Collections.unmodifiableList(features);
                	
                    return RepeatStatus.FINISHED;
                })
                .build();
    }
    
    @Bean
    public Step stepWriteData() {
        return steps.get("stepWriteData")
                .tasklet((contribution, chunkContext) -> {
                	logger.info(">>>>> This is stepWriteData");
                	
                	if (features.size() > 0) {
                		logger.info("Writing test data");
                        // use try-with-resources to ensure the writer is closed
                        try (FeatureWriter<SimpleFeatureType, SimpleFeature> writer =
                                 datastore.getFeatureWriterAppend(sft.getTypeName(), Transaction.AUTO_COMMIT)) {
                            for (SimpleFeature feature : features) {
                                // using a geotools writer, you have to get a feature, modify it, then commit it
                                // appending writers will always return 'false' for haveNext, so we don't need to bother checking
                                SimpleFeature toWrite = writer.next();

                                // copy attributes
                                toWrite.setAttributes(feature.getAttributes());

                                // if you want to set the feature ID, you have to cast to an implementation class
                                // and add the USE_PROVIDED_FID hint to the user data
                                 ((FeatureIdImpl) toWrite.getIdentifier()).setID(feature.getID());
                                 toWrite.getUserData().put(Hints.USE_PROVIDED_FID, Boolean.TRUE);

                                // alternatively, you can use the PROVIDED_FID hint directly
                                // toWrite.getUserData().put(Hints.PROVIDED_FID, feature.getID());

                                // if no feature ID is set, a UUID will be generated for you

                                // make sure to copy the user data, if there is any
                                toWrite.getUserData().putAll(feature.getUserData());

                                // write the feature
                                writer.write();
                            }
                        }
                        logger.info("Wrote " + features.size() + " features");
                    }
                	
                    return RepeatStatus.FINISHED;
                })
                .build();
    }
}