package burp.utils;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base64ByteArrayTypeAdapterTest {

    @Test
    void writesDeterministicPrefixedBase64ForEmptyTextBinaryAndEveryByte() throws Exception {
        Base64ByteArrayTypeAdapter adapter = new Base64ByteArrayTypeAdapter(1_024);
        byte[] allValues = new byte[256];
        IntStream.range(0, 256).forEach(index -> allValues[index] = (byte) index);

        assertThat(adapter.toJson(new byte[0])).isEqualTo("\"awb:b64:v1:\"");
        assertThat(adapter.fromJson(adapter.toJson("hello".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(adapter.fromJson(adapter.toJson(new byte[]{0, -1, 42, -128})))
                .containsExactly(0, -1, 42, -128);
        assertThat(adapter.fromJson(adapter.toJson(allValues))).isEqualTo(allValues);
        assertThat(adapter.toJson(allValues)).isEqualTo(adapter.toJson(allValues));
    }

    @Test
    void readsLegacySignedAndUnsignedNumericArraysWithoutBoxedByteSemantics() throws Exception {
        Base64ByteArrayTypeAdapter adapter = new Base64ByteArrayTypeAdapter(8);

        assertThat(adapter.fromJson("[-128,-1,0,1,127]"))
                .containsExactly(-128, -1, 0, 1, 127);
        assertThat(adapter.fromJson("[0,127,128,254,255]"))
                .containsExactly(0, 127, -128, -2, -1);
    }

    @Test
    void rejectsFractionsNonNumbersRangesPrefixesAndMalformedBase64() {
        Base64ByteArrayTypeAdapter adapter = new Base64ByteArrayTypeAdapter(8);

        assertThatThrownBy(() -> adapter.fromJson("[1.5]"))
                .isInstanceOf(JsonParseException.class).hasMessageContaining("integral");
        assertThatThrownBy(() -> adapter.fromJson("[true]"))
                .isInstanceOf(JsonParseException.class).hasMessageContaining("non-numeric");
        assertThatThrownBy(() -> adapter.fromJson("[-129]"))
                .isInstanceOf(JsonParseException.class).hasMessageContaining("-128..255");
        assertThatThrownBy(() -> adapter.fromJson("[256]"))
                .isInstanceOf(JsonParseException.class).hasMessageContaining("-128..255");
        assertThatThrownBy(() -> adapter.fromJson("\"b64:AAAA\""))
                .isInstanceOf(JsonParseException.class).hasMessageContaining("prefix");
        assertThatThrownBy(() -> adapter.fromJson("\"awb:b64:v1:not-base64!\""))
                .isInstanceOf(JsonParseException.class).hasMessageContaining("malformed");
    }

    @Test
    void enforcesEncodedPrecheckAndExactDecodedBoundary() throws Exception {
        Base64ByteArrayTypeAdapter adapter = new Base64ByteArrayTypeAdapter(3);
        byte[] exact = new byte[]{1, 2, 3};

        assertThat(adapter.fromJson(adapter.toJson(exact))).isEqualTo(exact);
        assertThatThrownBy(() -> adapter.toJson(new byte[]{1, 2, 3, 4}))
                .isInstanceOf(JsonParseException.class).hasMessageContaining("3 byte limit");
        assertThatThrownBy(() -> adapter.fromJson("\"awb:b64:v1:AAAAAA==\""))
                .isInstanceOf(JsonParseException.class).hasMessageContaining("3 byte limit");
        assertThatThrownBy(() -> adapter.fromJson("[1,2,3,4]"))
                .isInstanceOf(JsonParseException.class).hasMessageContaining("3 byte limit");
    }
}
