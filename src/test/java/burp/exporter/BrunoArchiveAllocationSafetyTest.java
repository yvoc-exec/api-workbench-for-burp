package burp.exporter;

import burp.models.ApiCollection;
import burp.models.ApiRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BrunoArchiveAllocationSafetyTest {
    @Test
    void archiveEntriesAreSafeUniqueAndCollisionAllocatedDeterministically() throws Exception {
        ApiCollection collection = new ApiCollection(); collection.name = "..\\Unsafe:/Root";
        collection.folderPaths.addAll(List.of(".", "..", "C:/absolute", "Case", "case"));
        collection.requests.add(request("collection", ""));
        collection.requests.add(request("folder", ""));
        collection.requests.add(request("Request", "Case"));
        collection.requests.add(request("request", "Case"));
        collection.requests.add(request("REQUEST", "Case"));
        collection.requests.add(request("../escape", ".."));

        byte[] first = export(collection); byte[] second = export(collection);
        assertThat(first).isEqualTo(second);
        List<String> names = names(first);
        Set<String> insensitive = new HashSet<>();
        for (String name : names) {
            assertThat(name).doesNotStartWith("/").doesNotStartWith("\\")
                    .doesNotContain("\\", "../", "/./");
            assertThat(name).doesNotMatch("^[A-Za-z]:.*");
            assertThat(name.split("/")).noneMatch(segment -> segment.equals(".") || segment.equals("..") || segment.isEmpty());
            assertThat(insensitive.add(name.toLowerCase(Locale.ROOT))).isTrue();
        }
        assertThat(names).anyMatch(name -> name.endsWith("Request.bru"));
        assertThat(names).anyMatch(name -> name.endsWith("request_2.bru"));
        assertThat(names).anyMatch(name -> name.endsWith("REQUEST_3.bru"));
        assertThat(names).noneMatch(name -> name.endsWith("/_collection.bru") || name.endsWith("/_folder.bru"));
    }

    private static ApiRequest request(String name, String path) {
        ApiRequest request = new ApiRequest(); request.name=name; request.path=path; request.method="GET";
        request.url="https://e.test"; request.body=new ApiRequest.Body(); request.body.mode="none"; return request;
    }
    private static byte[] export(ApiCollection collection) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); BrunoCollectionExporter.write(collection,null,out,new ArrayList<>());return out.toByteArray();
    }
    private static List<String> names(byte[] bytes) throws Exception {
        List<String> names=new ArrayList<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)){
            ZipEntry entry;while((entry=zip.getNextEntry())!=null)names.add(entry.getName());}return names;
    }
}
