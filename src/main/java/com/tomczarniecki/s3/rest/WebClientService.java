/* ===================================================================================
 * Copyright (c) 2008, Thomas Czarniecki
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *  * Neither the name of S3DropBox, Thomas Czarniecki, tomczarniecki.com nor
 *    the names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * ===================================================================================
 */
package com.tomczarniecki.s3.rest;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.Region;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;
import com.tomczarniecki.s3.ProgressListener;
import com.tomczarniecki.s3.S3Bucket;
import com.tomczarniecki.s3.S3Object;
import com.tomczarniecki.s3.Service;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.tomczarniecki.s3.Generics.newArrayList;
import static com.tomczarniecki.s3.Generics.newHashMap;

public class WebClientService implements Service {

    private final AmazonS3 client;
    private final TransferManager transferManager;
    private final Map<String, String> videoContentTypes;

    public WebClientService(Configuration config) {
        // proper HTML5 video content types so that browsers can play the videos
        Map<String, String> contentTypes = newHashMap();
        contentTypes.put("ogv", "video/ogg");
        contentTypes.put("mp4", "video/mp4");
        contentTypes.put("webm", "video/webm");

        videoContentTypes = Collections.unmodifiableMap(contentTypes);
        client = new AmazonS3Client(config.getAWSCredentials(), config.getClientConfiguration());
        transferManager = new TransferManager(client);
    }

    public List<String> bucketRegions() {
        List<String> regions = newArrayList();
        for (Region region : Region.values()) {
            regions.add(region.name());
        }
        return regions;
    }

    public List<S3Bucket> listAllMyBuckets() {
        List<S3Bucket> buckets = newArrayList();
        for (Bucket bucket : client.listBuckets()) {
            buckets.add(new S3Bucket(bucket.getName()));
        }
        return buckets;
    }

    public boolean bucketExists(String bucketName) {
        return client.doesBucketExist(bucketName);
    }

    public void createBucket(String bucketName, String region) {
        if (region != null) {
            client.createBucket(bucketName, Region.valueOf(region));
        } else {
            client.createBucket(bucketName);
        }
    }

    public void deleteBucket(String bucketName) {
        client.deleteBucket(bucketName);
    }

    public List<S3Object> listObjectsInBucket(String bucketName) {
        List<S3Object> objects = newArrayList();
        ObjectListing objectListing = client.listObjects(bucketName);
        for (S3ObjectSummary summary : objectListing.getObjectSummaries()) {
            DateTime lastModified = new DateTime(summary.getLastModified());
            lastModified = lastModified.toDateTime(DateTimeZone.getDefault());
            objects.add(new S3Object(summary.getKey(), summary.getSize(), lastModified.toLocalDateTime()));
        }
        Collections.sort(objects);
        return objects;
    }

    public boolean objectExists(String bucketName, String objectKey) {
        ObjectListing objectListing = client.listObjects(bucketName, objectKey);
        for (S3ObjectSummary summary : objectListing.getObjectSummaries()) {
            if (summary.getKey().equals(objectKey)) {
                return true;
            }
        }
        return false;
    }

    public void createObject(String bucketName, String objectKey, File source, ProgressListener listener) {
        PutObjectRequest request = new PutObjectRequest(bucketName, objectKey, source);
        String contentType = videoContentTypes.get(FilenameUtils.getExtension(source.getName()));
        if (contentType != null) {
            ObjectMetadata md = new ObjectMetadata();
            md.setContentType(contentType);
            request.setMetadata(md);
        }
        request.setProgressListener(new ProgressListenerAdaptor(listener, source.length()));
        try {
            Upload upload = transferManager.upload(request);
            upload.waitForCompletion();

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void downloadObject(String bucketName, String objectKey, File target, ProgressListener listener) {
        writeToFile(client.getObject(bucketName, objectKey), target, listener);
    }

    public String getPublicUrl(String bucketName, String objectKey, DateTime expires) {
        return client.generatePresignedUrl(bucketName, objectKey, expires.toDate()).toExternalForm();
    }

    public void deleteObject(String bucketName, String objectKey) {
        client.deleteObject(bucketName, objectKey);
    }

    public void removeFailedUploads(String bucketName) {
        transferManager.abortMultipartUploads(bucketName, new Date());
    }

    public void close() {
        transferManager.shutdownNow();
    }

    private void writeToFile(com.amazonaws.services.s3.model.S3Object object, File target, ProgressListener listener) {
        long fileLength = object.getObjectMetadata().getContentLength();
        OutputStream output = null;
        InputStream input = null;
        try {
            input = object.getObjectContent();
            output = new ProgressOutputStream(new FileOutputStream(target), listener, fileLength);
            IOUtils.copy(input, output);

        } catch (IOException e) {
            throw new RuntimeException(e);

        } finally {
            IOUtils.closeQuietly(input);
            IOUtils.closeQuietly(output);
        }
    }
}
