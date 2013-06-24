/**
 * Copyright 2013 Peergreen S.A.S.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.peergreen.community.maven;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;

import org.codehaus.plexus.util.IOUtil;

/**
 * Custom file writer used to add license header in the POM file.
 * @author Florent Benoit
 */
public class FileHeaderWriter extends FileWriter {

    /**
     * Header to be written.
     */
    private String header;

    /**
     * Flag to check if the header has already be written.
     */
    private boolean headerWritten = false;

    /**
     * Marker for the header.
     */
    private static final String HEADER_END_ELEMENT = "?>";

    /**
     * Delegates to the super class.
     * @param file the file used to write content
     * @throws IOException
     */
    public FileHeaderWriter(File file) throws IOException {
        super(file);
        init();
    }

    /**
     * Reads the header.
     * @throws IOException
     */
    protected void init() throws IOException {
        // loads the header
        InputStream is = FileHeaderWriter.class.getResourceAsStream("/header.txt");
        if (is == null) {
            throw new IllegalStateException("Unable to find the resource named header.txt");
        }

        this.header = IOUtil.toString(is);
    }

    /**
     * Writes a string.
     * @param str String to be written
     * @throws IOException If an I/O error occurs
     */
    @Override
    public void write(String str) throws IOException {
        super.write(str);
        // if writing top element write the header
        if (!headerWritten && str.equals(HEADER_END_ELEMENT)) {
            // write header
            super.write("\n");
            super.write(header);
            this.headerWritten = true;
        }
    }

}
