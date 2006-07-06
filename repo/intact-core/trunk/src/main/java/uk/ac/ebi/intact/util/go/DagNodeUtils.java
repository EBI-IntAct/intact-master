/*
Copyright (c) 2002-2004 The European Bioinformatics Institute, and others.
All rights reserved. Please see the file LICENSE
in the root directory of this distribution.
*/

package uk.ac.ebi.intact.util.go;

import uk.ac.ebi.intact.business.IntactException;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auxiliary class to insert GO Dag nodes from a GO dag formatted flat file.
 *
 * @author Henning Hermjakob (hhe@ebi.ac.uk), Sugath Mudali (smudali@ebi.ac.uk)
 */
public class DagNodeUtils {

    /**
     * The pattern to match a GO line.
     * pattern: a line beginning with multiple optional spaces, followed by @
     * or % or $ or < character and multiple chars.
     */
    private static final Pattern ourDagLineRegex =
            Pattern.compile("^(\\s*)([@\\%\\$\\<].*)$");

    /**
     * The pattern to extract GO id and term info from a GO line.
     * pattern: % or $ or < followed by multiple spaces, and the go term
     * (all the charcaters upto ;), ; follwed by multiple spaces and then the
     * GO id (all the characters upto a space or ,).
     */
    private static final Pattern ourGoIdRegex =
            Pattern.compile("[\\%\\$\\<]\\s*([^;]*)[\\; ]*([^\\s\\,]*)");

    /**
     * The pattern to extract GO id and term info from a GO line generated by a
     * DAG-Edit version 1.4
     * pattern: @is_a@go term ; go id or @is_a@shortlabel\: go term ; go id
     * space, ;, space and all the chars upto the first space as the GO id.
     */
    private static final Pattern ourGoId14Regex =
            Pattern.compile("\\s*@is_a@([^;]*)[\\; ]*([^;\\s]*)");

    /**
     * The pattern to extract the go term and short label for V14 format.
     * pattern: all the chars till \: for group 1 and the rest for group 2 (
     * group 2 is optional and it doesn't contain ': ').
     */
    private static final Pattern ourShortLabelGoTerm14Regx =
            Pattern.compile("([^\\\\:]*)[\\\\: ]*(.*)");

    /**
     * Pattern: synonym:[any characters apart for ;]
     */
    private static final Pattern ourSynonymRegx = Pattern.compile("synonym:([^;@]+)");

    /**
     * Reference to the GoTools.
     */
    private GoUtils myGoUtilsHandler;

    /**
     * Constructs with a reference to the GoTools.
     * @param handler the handler to the GoTools
     */
    public DagNodeUtils(GoUtils handler) {
        myGoUtilsHandler = handler;
    }

    /**
     * Read new nodes from the input, add nodes to the aParent node.
     *
     * @param in Go Dag format input source
     * @param aParent The node to which new nodes will be added.
     * @throws java.io.IOException for errors reading the input file
     * @throws IntactException for errors in dealing with the persistence layer.
     */
    public DagNode addNodes(BufferedReader in,DagNode aParent, int count)
            throws IOException, IntactException {
        DagNode current = null;

        // The while loop adds all nodes of the current level to the current aParent.
        // Return from the loop happens if one of the parsed nodes is on the same
        // or lower indentation level as the current aParent.
        while (true) {
            if (null == current) {
                // Read the next node from flat file
                current = nextDagNode(in);
                if (null == current) {

                    // The end of the flat file has been reached.
                    return null;
                }
                else {
                    // Progress report
                    System.out.print(".");
                }
            }

            if (current.isParent(aParent)) {
                if (null != aParent) {
                    current.setParent(aParent);
                }
                current.storeDagNode(myGoUtilsHandler);
                current = addNodes(in, current, count);
            }
            else {
                if (aParent == null) {
                    // Special case for the root node. It is the only node without a aParent.
                    // make node persistent
                    current.storeDagNode(myGoUtilsHandler);
                    // Recurse into the dag.
                    return addNodes(in, current, count);
                }
                else {
                    // A node has been found which has a lower indentation level than the current aParent.
                    // Return from the current level to the next higher node.
                    return current;
                }
            }
        }
    }

    /**
     * Returns the next DagNodeOld from a GO DAG file
     *
     * @param in the flat file data source
     * @throws java.io.IOException for I/O errors
     */
    private static DagNode nextDagNode(BufferedReader in) throws IOException {

        DagNode node = null;
        String nextLine = null;

        while (null != (nextLine = in.readLine())) {
            Matcher dagLineMatch = ourDagLineRegex.matcher(nextLine);
            if (dagLineMatch.matches()) {
                node = new DagNode();

                String leadingBlanks = dagLineMatch.group(1);
                String termLine = dagLineMatch.group(2);
                node.setIndentLevel(leadingBlanks.length());

                // A Go term may have more than one parent. Each line of a Go Dag
                // file contains the term, and optinal additional additionalParents.
                // The "main" parent is defined by a preceding line, the additional
                // additionalParents are defined in the current line and are
                // added as parent data of the current node.
                // Matcher for go id

                if (termLine.startsWith("@")) {
                    // Stores the go term.
                    String goTerm = null;

                    for (Matcher goIdMatch = ourGoId14Regex.matcher(termLine); goIdMatch.find(); ) {
                        Matcher m = ourShortLabelGoTerm14Regx.matcher(goIdMatch.group(1));
                        // Store goterm
                        if (m.find()) {
                            // If there is no go term, set it to as same as short label
                            goTerm = m.group(2).length() == 0 ? m.group(1) : m.group(2);
                        }
                        if (null == node.getGoId()) {
                            // Store GO info.
                            node.setGoShortLabel(m.group(1));
                            node.setGoTerm(goTerm);
                            node.setGoId(goIdMatch.group(2));
                        }
                        else {
                            // Must be the parent data.
                            node.addParentData(goIdMatch.group(2), goTerm, m.group(1));
                        }
                        // Any synoyms?
                        for (Matcher m1 = ourSynonymRegx.matcher(termLine); m1.find();) {
                            node.addAlias(m1.group(1).trim());
                        }
                    }
                }
                else {
                    // Encountered the first line in the dag file (starts with $)
                    Matcher goIdMatch = ourGoIdRegex.matcher(termLine);
                    if (goIdMatch.find()) {
                        // There is no node for given go id (as this is the first one)
                        // Store goid
                        node.setGoId(goIdMatch.group(2));
                        // Store goterm
                        node.setGoTerm(goIdMatch.group(1).trim());
                        // Set the short label for search.
                        node.setGoShortLabelFromTerm();
                        // Any synoyms?
                        for (Matcher m1 = ourSynonymRegx.matcher(termLine); m1.find();) {
                            node.addAlias(m1.group(1).trim());
                        }
                    }
                }
                return node;
            }
        }
        return node;
    }
}
