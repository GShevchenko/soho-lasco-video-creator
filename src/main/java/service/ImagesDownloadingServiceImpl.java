package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.istack.NotNull;
import domain.IImageMetadata;
import domain.ImageMetadata;
import domain.MetaTotal;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@Data
@Getter
@Setter
@RequiredArgsConstructor
@NoArgsConstructor
public class ImagesDownloadingServiceImpl implements ImagesDownloadingService {
    public static final long PREVIEW_NOT_AVAILABLE_JPEG_SIZE = 12978;
    public static final String JPEG_LIST_FILE_NAME_FOR_FFMPEG = "images.txt";
    public static final String AUDIO_LIST_FILE_FOR_FFMPEG = "audio.txt";
    public static String templateQuery = "http://ssa.esac.esa.int/ssa/aio/metadata-action?RESOURCE_CLASS=OBSERVATION&SELECTED_FIELDS=OBSERVATION&QUERY=(INSTRUMENT.NAME=='LASCO'+AND+OBSERVING_MODE.NAME=='C3')+AND+OBSERVATION.BEGINDATE>'%s'+AND+OBSERVATION.BEGINDATE<'%s'&RETURN_TYPE=JSON&ORDER_BY=OBSERVATION.BEGINDATE";
    public static String mockQuery = "http://ssa.esac.esa.int/ssa/aio/metadata-action?RESOURCE_CLASS=OBSERVATION&SELECTED_FIELDS=OBSERVATION&QUERY=(INSTRUMENT.NAME==%27LASCO%27+AND+OBSERVING_MODE.NAME==%27C3%27)+AND+OBSERVATION.BEGINDATE%3E%272009-01-01%2023:18:22.588%27+AND+OBSERVATION.BEGINDATE%3C%272009-01-02%2000:18:30.945%27&RETURN_TYPE=JSON&ORDER_BY=OBSERVATION.BEGINDATE";
    public static String mock2 = "http://ssa.esac.esa.int/ssa/aio/metadata-action?RESOURCE_CLASS=OBSERVATION&SELECTED_FIELDS=OBSERVATION&QUERY=(INSTRUMENT.NAME=='LASCO'+AND+OBSERVING_MODE.NAME=='C3')+AND+OBSERVATION.BEGINDATE>'2009-11-01%2000:02'+AND+OBSERVATION.BEGINDATE<'2009-11-01%2014:02'&RETURN_TYPE=JSON&ORDER_BY=OBSERVATION.BEGINDATE";
    public static String mock3 = "http://ssa.esac.esa.int/ssa/aio/metadata-action?RESOURCE_CLASS=OBSERVATION&SELECTED_FIELDS=OBSERVATION&QUERY=(INSTRUMENT.NAME=='LASCO'+AND+OBSERVING_MODE.NAME=='C3')+AND+OBSERVATION.BEGINDATE>'2009-11-01'+AND+OBSERVATION.BEGINDATE<'2009-11-02'&RETURN_TYPE=JSON&ORDER_BY=OBSERVATION.BEGINDATE";
    @NonNull
    private LocalDateTime observStartDate;
    @NotNull
    private LocalDateTime observEndDate;
    private MetaTotal metaDataTotal;
    private List<IImageMetadata> successfullyDownloadedImages = new ArrayList<>();
    public Path currentFolderWithJpegFile;
    private int imagesCount;

    private String query;

    public ImagesDownloadingServiceImpl(LocalDateTime observStartDate, LocalDateTime observEndDate) {
        this.observStartDate = observStartDate;
        this.observEndDate = observEndDate;
        this.query = String.format(templateQuery, removeTFromDateTime(observStartDate), removeTFromDateTime(observEndDate));
    }

    public String getQuery() {
     return query = String.format(templateQuery, removeTFromDateTime(observStartDate), removeTFromDateTime(observEndDate));
    }
    public void downloadImagesMetadata() throws IOException {
        log.info("ImagesDownloadingServiceImpl.downloadImagesMetadata. Query is {}", getQuery());
        URL url = new URL(getQuery());
        log.info("ImagesDownloadingServiceImpl.downloadImagesMetadata. {}", url.getQuery());
        ObjectMapper objectMapper = new ObjectMapper();
        metaDataTotal = objectMapper.readValue(url, MetaTotal.class);
        url.openStream().close();
    }

    public Integer downloadImagesParallel() {
        successfullyDownloadedImages.clear();
        log.info("SecondMainClass.downloadImagesParallel. imageDataList size={}", metaDataTotal.getTotal());
        try {
            createFolderForJpegs();
        } catch (IOException e) {
            log.info("ImagesDownloadingServiceImpl.downloadImages" + e.getStackTrace());
            return 0;
        }
        this.metaDataTotal.getData().parallelStream().forEach(this::downloadImage);
        metaDataTotal.getData().clear();
        log.info("ImagesDownloadingServiceImpl.downloadImagesParallel. jpeg with size > 100Kb is {}", successfullyDownloadedImages.size());
        imagesCount = successfullyDownloadedImages.size();
        return imagesCount;
    }

    @Override
    public int downloadImages() {
        successfullyDownloadedImages.clear();

        log.info("SecondMainClass.downloadAllC3Images. imageDataList size={}", metaDataTotal.getTotal());
        int count = 0;
        try {
            createFolderForJpegs();
        } catch (IOException e) {
            log.info("ImagesDownloadingServiceImpl.downloadImages" + e.getStackTrace());
            return 0;
        }
        for (IImageMetadata imageData : metaDataTotal.getData()) {
            if (imageData.isContainC3Image()) {
                try {
                    URL url = new URL(imageData.get1024JpegUrl());
                    log.info("ImagesDownloadingService.downloadImages. Try to download image {}", imageData.get1024JpegUrl());
                    ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
                    FileOutputStream fileOutputStream = new FileOutputStream(getFolderNameForJpegs() + imageData.getJpegFileName());
                    long fileSize = fileOutputStream.getChannel()
                            .transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
                    if (fileSize > PREVIEW_NOT_AVAILABLE_JPEG_SIZE) {
                        successfullyDownloadedImages.add(imageData);
                    }
                    count++;
                    readableByteChannel.close();
                    fileOutputStream.close();
                } catch (IOException ex) {
                    log.error("ImagesDownloadingServiceImpl.downloadImages. " + ex);
                }
            }
        }
        log.info("ImagesDownloadingServiceImpl.downloadImages. Total count is {}, jpeg with size > 100Kb is {}", count, successfullyDownloadedImages.size());

        return imagesCount;
    }

    public void downloadImage(ImageMetadata imageMetadata) {
        try {
            URL url = new URL(imageMetadata.get1024JpegUrl());
            log.info("ImagesDownloadingService.downloadImage. Try to download URL={}", url.getQuery());
            ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
            FileOutputStream fileOutputStream = new FileOutputStream(getFolderNameForJpegs() + imageMetadata.getJpegFileName());
            long fileSize = fileOutputStream.getChannel()
                    .transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
            imageMetadata.setJpegSize(fileSize);
            successfullyDownloadedImages.add(imageMetadata);

            readableByteChannel.close();
            fileOutputStream.close();
        } catch (IOException ex) {
            log.error("ImagesDownloadingServiceImpl.downloadImages. " + ex);
        }
    }

    @Override
    public void createListImagesFileForFmpeg() {
        Path path = Paths.get(getFolderNameForJpegs() + "images.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            successfullyDownloadedImages.sort(Comparator.comparing(
                    o -> LocalDateTime.parse(o.getBeginObservationDate().split("\\.")[0], DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
            IImageMetadata previousPreviewAvailable = successfullyDownloadedImages.stream().filter(im -> ((ImageMetadata)im).getJpegSize() > PREVIEW_NOT_AVAILABLE_JPEG_SIZE).findFirst().get();
            for (IImageMetadata imageMetadata : successfullyDownloadedImages) {
                if (((ImageMetadata) imageMetadata).getJpegSize() > PREVIEW_NOT_AVAILABLE_JPEG_SIZE) {
                    previousPreviewAvailable = imageMetadata;
                    writer.write("file '" + getFolderNameForJpegs() + imageMetadata.getJpegFileName() + "'");
                } else {
                    writer.write("file '" + getFolderNameForJpegs() + previousPreviewAvailable.getJpegFileName() + "'");
                }
                writer.newLine();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }


    public Path getCurrentFolderWithJpegFile() {
        return currentFolderWithJpegFile;
    }

    public Optional<Path> setJpegDir(File parentDir) {
        log.info("ImagesDownloadingServiceImpl.setJpegDir. parentDir={}", parentDir);
        if (!parentDir.isDirectory()) {
            return Optional.empty();
        }
        Optional<File> listJpegFile = Arrays.stream(parentDir.listFiles()).filter(f -> "images.txt".equals(f.getName()))
                .findFirst();
        listJpegFile.ifPresent(file -> {
            this.currentFolderWithJpegFile = Paths.get(file.getAbsolutePath());
            this.initData(currentFolderWithJpegFile);
        });
        log.info("ImagesDownloadingServiceImpl.setJpegDir. currentFolderWithJpegFile={}", currentFolderWithJpegFile);
        return Optional.ofNullable(currentFolderWithJpegFile);
    }

    private void initData(Path pathToJpegListFile) {
        log.info("ImagesDownloadingServiceImpl.initData. dirWithJpeg={}", pathToJpegListFile);
        try (Stream<String> stream = Files.lines(pathToJpegListFile, StandardCharsets.UTF_8)) {
            //TODO возможно лучше сделать поле, в котором хранить количество записей в текстовом файле
            imagesCount = (int) stream.count();
        } catch (IOException exception) {
            log.error("ImagesDownloadingService.initData\n ", exception);
        }
        setDateBoundaries(pathToJpegListFile.getParent().getFileName().toString());
    }
    private void setDateBoundaries(String currentFolderWithJpegFile) {
        log.info("ImagesDownloadingServiceImpl.setDateBoundaries. currentFolderWithJpegFile={}", currentFolderWithJpegFile);
        String[] doubleDateTime = currentFolderWithJpegFile.split("_");
        this.observStartDate = parseToLocalDateTime(doubleDateTime[0], doubleDateTime[1]);
        this.observEndDate = parseToLocalDateTime(doubleDateTime[2], doubleDateTime[3]);
    }

    private LocalDateTime parseToLocalDateTime(String date, String time) {
        LocalDate localDate = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
        LocalTime localTime = LocalTime.parse(time, DateTimeFormatter.ofPattern("HHmm"));
        return LocalDateTime.of(localDate, localTime);
    }

    @Override
    public String getPathToJpegListFile() {
        return getFolderNameForJpegs() + JPEG_LIST_FILE_NAME_FOR_FFMPEG;
    }

    public void createFolderForJpegs() throws IOException {
        Path directories = Files.createDirectories(Paths.get(getFolderNameForJpegs()));
        setCurrentFolderWithJpegFile(Paths.get(directories.getFileName() + "images.txt"));
    }

    public String getFolderNameForJpegs() {
        return formatDateTimeForFolderName(observStartDate) + "_" + formatDateTimeForFolderName(observEndDate) + File.separator;
    }

    private String formatDateTimeForFolderName(LocalDateTime dateTime) {
        return DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").format(dateTime);
    }

    public static String removeTFromDateTime(LocalDateTime dateTimeWithT) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd%20HH:mm").format(dateTimeWithT);
    }

    public int calculateVideoRate(double audioDuration) {
        log.info("ImagesDownloadingServiceImpl.calculateVideoRate. audioDuration={}, imagesCount={}", audioDuration, imagesCount);
        return (int) Math.round(imagesCount / audioDuration);
    }
}
