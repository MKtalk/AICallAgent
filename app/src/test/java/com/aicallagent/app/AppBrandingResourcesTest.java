package com.aicallagent.app;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppBrandingResourcesTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test
    public void defaultResourcesAreEnglishAndBranded() throws Exception {
        Map<String, String> values = loadStrings("res/values/strings.xml");
        assertEquals("AI Call Agent", values.get("app_name"));
        assertEquals("AI Call Agent Accessibility Service", values.get("accessibility_service_label"));
        assertEquals("Accessibility service connected", values.get("status_service_connected"));
    }

    @Test
    public void koreanResourcesAreLocalizedAndBranded() throws Exception {
        Map<String, String> values = loadStrings("res/values-ko/strings.xml");
        assertEquals("AI Call Agent", values.get("app_name"));
        assertEquals("AI Call Agent 접근성 서비스", values.get("accessibility_service_label"));
        assertEquals("접근성 서비스 연결됨", values.get("status_service_connected"));
    }

    @Test
    public void allStringKeysAreSynchronizedBetweenDefaultAndKorean() throws Exception {
        Map<String, String> defaultStrings = loadStrings("res/values/strings.xml");
        Map<String, String> koStrings = loadStrings("res/values-ko/strings.xml");

        assertFalse("Default strings must not be empty", defaultStrings.isEmpty());
        assertFalse("Korean strings must not be empty", koStrings.isEmpty());
        assertEquals("String resource keys between default and Korean must match exactly",
                defaultStrings.keySet(), koStrings.keySet());
    }

    private static Map<String, String> loadStrings(String relativePath) throws Exception {
        Document strings = parseXml(resolveMainPath(relativePath));
        NodeList nodes = strings.getElementsByTagName("string");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            values.put(element.getAttribute("name"), element.getTextContent());
        }
        return values;
    }

    @Test
    public void manifestUsesAdaptiveLauncherIconsAndAppNameLabel() throws Exception {
        Document manifest = parseXml(resolveMainPath("AndroidManifest.xml"));
        NodeList applications = manifest.getElementsByTagName("application");
        assertEquals(1, applications.getLength());

        Element application = (Element) applications.item(0);
        assertEquals("@string/app_name", application.getAttributeNS(ANDROID_NS, "label"));
        assertEquals("@mipmap/ic_launcher", application.getAttributeNS(ANDROID_NS, "icon"));
        assertEquals("@mipmap/ic_launcher_round", application.getAttributeNS(ANDROID_NS, "roundIcon"));
    }

    @Test
    public void adaptiveLauncherIconsDeclareValid108DpVectorLayers() throws Exception {
        verifyAdaptiveIcon("res/mipmap-anydpi/ic_launcher.xml");
        verifyAdaptiveIcon("res/mipmap-anydpi/ic_launcher_round.xml");

        verifyVectorLayer("res/drawable/ic_launcher_background.xml");
        verifyVectorLayer("res/drawable/ic_launcher_foreground.xml");
        verifyVectorLayer("res/drawable/ic_launcher_monochrome.xml");
    }

    private static void verifyAdaptiveIcon(String relativePath) throws Exception {
        Path iconPath = resolveMainPath(relativePath);
        assertTrue("Missing adaptive icon: " + relativePath, Files.exists(iconPath));

        Document doc = parseXml(iconPath);
        Element root = doc.getDocumentElement();
        assertEquals("adaptive-icon", root.getTagName());
        assertEquals("@drawable/ic_launcher_background", childDrawable(root, "background"));
        assertEquals("@drawable/ic_launcher_foreground", childDrawable(root, "foreground"));
        assertEquals("@drawable/ic_launcher_monochrome", childDrawable(root, "monochrome"));
    }

    private static String childDrawable(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        assertEquals("Expected one <" + tagName + "> element", 1, children.getLength());
        return ((Element) children.item(0)).getAttributeNS(ANDROID_NS, "drawable");
    }

    private static void verifyVectorLayer(String relativePath) throws Exception {
        Path vectorPath = resolveMainPath(relativePath);
        assertTrue("Missing vector drawable: " + relativePath, Files.exists(vectorPath));

        Document doc = parseXml(vectorPath);
        Element root = doc.getDocumentElement();
        assertEquals("vector", root.getTagName());
        assertEquals("108dp", root.getAttributeNS(ANDROID_NS, "width"));
        assertEquals("108dp", root.getAttributeNS(ANDROID_NS, "height"));
        assertEquals("108", root.getAttributeNS(ANDROID_NS, "viewportWidth"));
        assertEquals("108", root.getAttributeNS(ANDROID_NS, "viewportHeight"));

        NodeList paths = root.getElementsByTagName("path");
        assertTrue("Expected at least one <path> in " + relativePath, paths.getLength() > 0);
        for (int i = 0; i < paths.getLength(); i++) {
            Element path = (Element) paths.item(i);
            String pathData = path.getAttributeNS(ANDROID_NS, "pathData");
            assertFalse("Empty pathData in " + relativePath, pathData == null || pathData.trim().isEmpty());
        }
    }

    private static Path resolveMainPath(String relativePath) {
        Path moduleRelative = Paths.get("src/main", relativePath);
        if (Files.exists(moduleRelative) || Files.exists(Paths.get("src/main/AndroidManifest.xml"))) {
            return moduleRelative;
        }
        return Paths.get("app/src/main", relativePath);
    }

    private static Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        try (InputStream in = Files.newInputStream(path)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }
}
