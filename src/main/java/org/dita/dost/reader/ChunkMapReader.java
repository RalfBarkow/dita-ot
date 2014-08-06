/*
 * This file is part of the DITA Open Toolkit project.
 * See the accompanying license.txt file for applicable licenses.
 */

/*
 * (c) Copyright IBM Corp. 2007 All Rights Reserved.
 */
package org.dita.dost.reader;

import static org.dita.dost.util.Constants.*;
import static org.dita.dost.writer.DitaWriter.*;
import static org.dita.dost.util.FileUtils.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.dita.dost.exception.DITAOTException;
import org.dita.dost.module.ChunkModule.ChunkFilenameGeneratorFactory;
import org.dita.dost.module.ChunkModule.ChunkFilenameGenerator;
import org.dita.dost.util.Job;
import org.dita.dost.util.XMLUtils;
import org.dita.dost.writer.AbstractDomFilter;
import org.dita.dost.writer.ChunkTopicParser;
import org.w3c.dom.*;

/**
 * ChunkMapReader class, read ditamap file for chunking.
 * 
 */
public final class ChunkMapReader extends AbstractDomFilter {

    public static final String FILE_NAME_STUB_DITAMAP = "stub.ditamap";
    public static final String FILE_EXTENSION_CHUNK = ".chunk";
    public static final String ATTR_XTRF_VALUE_GENERATED = "generated_by_chunk";

    public static final String CHUNK_BY_DOCUMENT = "by-document";
    public static final String CHUNK_BY_TOPIC = "by-topic";
    public static final String CHUNK_TO_CONTENT = "to-content";
    public static final String CHUNK_TO_NAVIGATION = "to-navigation";

    private String defaultChunkByToken;

    /** Input file's parent directory */
    private File filePath = null;
    // ChunkTopicParser assumes keys and values are chimera paths, i.e. systems paths with fragments.
    private final LinkedHashMap<String, String> changeTable = new LinkedHashMap<String, String>(128);

    private final Map<String, String> conflictTable = new HashMap<String, String>(128);

    private final Set<String> refFileSet = new HashSet<String>(128);

    private boolean supportToNavigation;

    private ProcessingInstruction workdir = null;
    private ProcessingInstruction workdirUrl = null;
    private ProcessingInstruction path2proj = null;
    private ProcessingInstruction path2projUrl = null;

    private String processingRole = ATTR_PROCESSING_ROLE_VALUE_NORMAL;
    private final ChunkFilenameGenerator chunkFilenameGenerator = ChunkFilenameGeneratorFactory.newInstance();
    private Job job;

    /**
     * Constructor.
     */
    public ChunkMapReader() {
        super();
    }

    public void setJob(final Job job) {
        this.job = job;
    }
    
    private File inputFile;
    
    /**
     * read input file.
     * 
     * @param inputFile filename
     */
    @Override
    public void read(final File inputFile) {
        this.inputFile = inputFile;
        filePath = inputFile.getParentFile();

        super.read(inputFile);
    }

    @Override
    public Document process(final Document doc) {
        readProcessingInstructions(doc);

        final Element root = doc.getDocumentElement();
        final String rootChunkValue = root.getAttribute(ATTRIBUTE_NAME_CHUNK);
        defaultChunkByToken = getChunkByToken(rootChunkValue, CHUNK_BY_DOCUMENT);
        // chunk value = "to-content"
        // When @chunk="to-content" is specified on "map" element,
        // chunk module will change its @class attribute to "topicref"
        // and process it as if it were a normal topicref wich
        // @chunk="to-content"
        if (rootChunkValue != null && rootChunkValue.contains(CHUNK_TO_CONTENT)) {
            chunkMap(root);
        } else {
            // if to-content is not specified on map element
            // process the map element's immediate child node(s)
            // get the immediate child nodes
            final NodeList list = root.getChildNodes();
            for (int i = 0; i < list.getLength(); i++) {
                final Node node = list.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    final Element currentElem = (Element) node;
                    if (MAP_RELTABLE.matches(currentElem)) {
                        updateReltable(currentElem);
                    } else if (MAP_TOPICREF.matches(currentElem) && !MAPGROUP_D_TOPICGROUP.matches(currentElem)) {
                        processTopicref(currentElem);
                    }

                }
            }
        }

        return buildOutputDocument(root);
    }

    private String getChunkByToken(final String chunkValue, final String defaultToken) {
        if (chunkValue == null || chunkValue.isEmpty()) {
            return defaultToken;
        }
        for (final String token: chunkValue.split("\\s+")) {
            if (token.startsWith("by-")) {
                return token;
            }
        }
        return defaultToken;
    }

    /**
     * Process map when "to-content" is specified on map element.
      */
    private void chunkMap(Element root) {
        // create the reference to the new file on root element.
        String newFilename = replaceExtension(inputFile.getName(), FILE_EXTENSION_DITA);
        File newFile = new File(inputFile.getParentFile().getAbsolutePath(), newFilename);
        if (newFile.exists()) {
            newFilename = chunkFilenameGenerator.generateFilename("Chunk", FILE_EXTENSION_DITA);
            final String oldpath = newFile.getAbsolutePath();
            newFile = resolve(inputFile.getParentFile().getAbsolutePath(), newFilename);
            // Mark up the possible name changing, in case that references might be updated.
            conflictTable.put(newFile.getAbsolutePath(), normalize(oldpath).getPath());
        }
        changeTable.put(newFile.getAbsolutePath(), newFile.getAbsolutePath());

        // change the class attribute to "topicref"
        final String originClassValue = root.getAttribute(ATTRIBUTE_NAME_CLASS);
        root.setAttribute(ATTRIBUTE_NAME_CLASS, originClassValue + MAP_TOPICREF.matcher);
        root.setAttribute(ATTRIBUTE_NAME_HREF, newFilename);

        // create the new topic stump
        OutputStream newFileWriter = null;
        try {
            newFileWriter = new FileOutputStream(newFile);
            final XMLStreamWriter o = XMLOutputFactory.newInstance().createXMLStreamWriter(newFileWriter, UTF8);
            o.writeStartDocument();
            o.writeProcessingInstruction(PI_WORKDIR_TARGET, UNIX_SEPARATOR + newFile.getParentFile().getAbsolutePath());
            o.writeProcessingInstruction(PI_WORKDIR_TARGET_URI, newFile.getParentFile().toURI().toString());
            o.writeStartElement(ELEMENT_NAME_DITA);
            o.writeEndElement();
            o.writeEndDocument();
            o.close();
            newFileWriter.flush();
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            try {
                if (newFileWriter != null) {
                    newFileWriter.close();
                }
            } catch (final Exception e) {
                logger.error(e.getMessage(), e);
            }
        }

        // process chunk
        processTopicref(root);

        // restore original root element
        if (originClassValue != null) {
            root.setAttribute(ATTRIBUTE_NAME_CLASS, originClassValue);
        }
        root.removeAttribute(ATTRIBUTE_NAME_HREF);
    }

    /**
     * Read processing metadata from processing instructions.
     */
    private void readProcessingInstructions(final Document doc) {
        final NodeList docNodes = doc.getChildNodes();
        for (int i = 0; i < docNodes.getLength(); i++) {
            final Node node = docNodes.item(i);
            if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
                final ProcessingInstruction pi = (ProcessingInstruction) node;
                if (pi.getNodeName().equals(PI_WORKDIR_TARGET)) {
                    workdir = pi;
                } else if (pi.getNodeName().equals(PI_WORKDIR_TARGET_URI)) {
                    workdirUrl = pi;
                } else if (pi.getNodeName().equals(PI_PATH2PROJ_TARGET)) {
                    path2proj = pi;
                } else if (pi.getNodeName().equals(PI_PATH2PROJ_TARGET_URI)) {
                    path2projUrl = pi;
                }
            }
        }
    }

    private void outputMapFile(final File file, final Document doc) {  
        OutputStream output = null;
        try {
            output = new FileOutputStream(file);
            final Transformer t = TransformerFactory.newInstance().newTransformer();
            t.transform(new DOMSource(doc), new StreamResult(output));
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            try {
                if (output != null) {
                    output.close();
                }
            } catch (final Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private Document buildOutputDocument(final Element root) {
        final Document doc = XMLUtils.getDocumentBuilder().newDocument();
        if (workdir != null) {
            doc.appendChild(doc.importNode(workdir, true));
        }
        if (workdirUrl != null) {
            doc.appendChild(doc.importNode(workdirUrl, true));
        }
        if (path2proj != null) {
            doc.appendChild(doc.importNode(path2proj, true));
        }
        if (path2projUrl != null) {
            doc.appendChild(doc.importNode(path2projUrl, true));
        }
        doc.appendChild(doc.importNode(root, true));
        return doc;
    } 

    // process chunk
    private void processTopicref(final Element topicref) {
        String hrefValue = null;
        String chunkValue = null;
        String copytoValue = null;
        String scopeValue = null;
        String classValue = null;
        String xtrfValue = null;
        String processValue = null;
        final String tempRole = processingRole;

        final Attr hrefAttr = topicref.getAttributeNode(ATTRIBUTE_NAME_HREF);
        final Attr chunkAttr = topicref.getAttributeNode(ATTRIBUTE_NAME_CHUNK);
        final Attr copytoAttr = topicref.getAttributeNode(ATTRIBUTE_NAME_COPY_TO);
        final Attr scopeAttr = topicref.getAttributeNode(ATTRIBUTE_NAME_SCOPE);
        final Attr classAttr = topicref.getAttributeNode(ATTRIBUTE_NAME_CLASS);
        final Attr xtrfAttr = topicref.getAttributeNode(ATTRIBUTE_NAME_XTRF);
        final Attr processAttr = topicref.getAttributeNode(ATTRIBUTE_NAME_PROCESSING_ROLE);

        if (hrefAttr != null) {
            hrefValue = hrefAttr.getNodeValue();
        }
        if (chunkAttr != null) {
            chunkValue = chunkAttr.getNodeValue();
        }
        if (copytoAttr != null) {
            copytoValue = copytoAttr.getNodeValue();
        }
        if (scopeAttr != null) {
            scopeValue = scopeAttr.getNodeValue();
        }
        if (classAttr != null) {
            classValue = classAttr.getNodeValue();
        }
        if (xtrfAttr != null) {
            xtrfValue = xtrfAttr.getNodeValue();
        }
        if (processAttr != null) {
            processValue = processAttr.getNodeValue();
            processingRole = processValue;
        }
        // This file is chunked(by-topic)
        if (xtrfValue != null && xtrfValue.contains(ATTR_XTRF_VALUE_GENERATED)) {
            return;
        }

        final String chunkByToken = getChunkByToken(chunkValue, defaultChunkByToken);

        if (ATTR_SCOPE_VALUE_EXTERNAL.equals(scopeValue)
                || (hrefValue != null && !resolve(filePath, hrefValue).exists())
                || (MAPGROUP_D_TOPICHEAD.matches(classValue) && chunkValue == null)
                || (MAP_TOPICREF.matches(classValue) && chunkValue == null && hrefValue == null)) {
            // Skip external links or non-existing href files.
            // Skip topic head entries.
            processChildTopicref(topicref);
        } else if (chunkValue != null && chunkValue.contains(CHUNK_TO_CONTENT)
                && (hrefAttr != null || copytoAttr != null || topicref.hasChildNodes())) {
            processChunk(topicref, false);
        } else if (chunkValue != null && chunkValue.contains(CHUNK_TO_NAVIGATION)
                && supportToNavigation) {
            processChildTopicref(topicref);
            // create new map file
            // create new map's root element
            final Element root = (Element) topicref.getOwnerDocument().getDocumentElement().cloneNode(false);
            // create navref element
            final Element navref = topicref.getOwnerDocument().createElement(MAP_NAVREF.localName);
            final String newMapFile = chunkFilenameGenerator.generateFilename("MAPCHUNK", FILE_EXTENSION_DITAMAP);
            navref.setAttribute(ATTRIBUTE_NAME_MAPREF, newMapFile);
            navref.setAttribute(ATTRIBUTE_NAME_CLASS, MAP_NAVREF.toString());
            // replace topicref with navref
            topicref.getParentNode().replaceChild(navref, topicref);
            root.appendChild(topicref);
            // generate new file
            final File navmap = resolve(filePath, newMapFile);
            changeTable.put(navmap.getPath(), navmap.getPath());
            outputMapFile(navmap, buildOutputDocument(root));
        } else if (chunkByToken.equals(CHUNK_BY_TOPIC)) {
            // TODO very important start point(by-topic).
            processChunk(topicref, true);
            processChildTopicref(topicref);
        } else {
            String currentPath = null;
            if (copytoValue != null) {
                currentPath = resolve(filePath, copytoValue).getPath();
            } else if (hrefValue != null) {
                currentPath = resolve(filePath, hrefValue).getPath();
            }
            if (currentPath != null) {
                if (changeTable.containsKey(currentPath)) {
                    changeTable.remove(currentPath);
                }
                if (!refFileSet.contains(currentPath)) {
                    refFileSet.add(currentPath);
                }
            }

            if ((chunkValue != null || chunkByToken.equals(CHUNK_BY_DOCUMENT))
                    && currentPath != null
                    && !ATTR_PROCESSING_ROLE_VALUE_RESOURCE_ONLY.equals(processingRole)) {
                changeTable.put(currentPath, currentPath);
            }
            processChildTopicref(topicref);
        }

        processingRole = tempRole;
    }

    private void processChildTopicref(final Element node) {
        final NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node current = children.item(i);
            if (current.getNodeType() == Node.ELEMENT_NODE) {
                final Element currentElem = (Element) current;
                final String classValue = currentElem.getAttribute(ATTRIBUTE_NAME_CLASS);
                if (MAP_TOPICREF.matches(classValue)) {
                    final String hrefValue = currentElem.getAttribute(ATTRIBUTE_NAME_HREF);
                    final String xtrfValue = currentElem.getAttribute(ATTRIBUTE_NAME_XTRF);
                    if (hrefValue.length() == 0 || MAPGROUP_D_TOPICHEAD.matches(classValue)) {
                        processTopicref(currentElem);
                    } else if (!ATTR_XTRF_VALUE_GENERATED.equals(xtrfValue)
                            && !resolve(filePath, hrefValue).getPath().equals(changeTable.get(resolve(filePath, hrefValue).getPath()))) {
                        processTopicref(currentElem);
                    }
                }
            }
        }
    }

    private void processChunk(final Element topicref, final boolean separate) {
        try {
            final ChunkTopicParser chunkParser = new ChunkTopicParser();
            chunkParser.setLogger(logger);
            chunkParser.setJob(job);
            chunkParser.setup(changeTable, conflictTable, refFileSet, topicref, separate, chunkFilenameGenerator);
            chunkParser.write(filePath);
        } catch (final DITAOTException e) {
            logger.error("Failed to process chunk: " + e.getMessage(), e);
        }
    }

    private void updateReltable(final Element elem) {
        final String hrefValue = elem.getAttribute(ATTRIBUTE_NAME_HREF);
        if (hrefValue.length() != 0) {
            if (changeTable.containsKey(resolve(filePath, hrefValue).getPath())) {
                String resulthrefValue = getRelativeUnixPath(filePath + UNIX_SEPARATOR + FILE_NAME_STUB_DITAMAP,
                                                             resolve(filePath, hrefValue).getPath());
                final String fragment = getFragment(hrefValue);
                if (fragment != null) {
                    resulthrefValue = resulthrefValue + fragment;
                }
                elem.setAttribute(ATTRIBUTE_NAME_HREF, resulthrefValue);
            }
        }
        final NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node current = children.item(i);
            if (current.getNodeType() == Node.ELEMENT_NODE) {
                final Element currentElem = (Element) current;
                final String classValue = currentElem.getAttribute(ATTRIBUTE_NAME_CLASS);
                if (MAP_TOPICREF.matches(classValue)) {
                    // FIXME: What should happen here?
                }
            }
        }
    }

    /**
     * Get changed files table.
     * 
     * @return map of changed files
     */
    public Map<String, String> getChangeTable() {
        return Collections.unmodifiableMap(changeTable);
    }

    /**
     * get conflict table.
     * 
     * @return conflict table
     */
    public Map<String, String> getConflicTable() {
        return conflictTable;
    }

    /**
     * Support chunk token to-navigation.
     * 
     * @param supportToNavigation flag to enable to-navigation support
     */
    public void supportToNavigation(final boolean supportToNavigation) {
        this.supportToNavigation = supportToNavigation;
    }

}
