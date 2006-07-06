/*
 * Copyright (c) 2002 The European Bioinformatics Institute, and others.
 * All rights reserved. Please see the file LICENSE
 * in the root directory of this distribution.
 */
package uk.ac.ebi.intact.util.controlledVocab;

import uk.ac.ebi.intact.business.BusinessConstants;
import uk.ac.ebi.intact.business.IntactException;
import uk.ac.ebi.intact.business.IntactHelper;
import uk.ac.ebi.intact.model.*;
import uk.ac.ebi.intact.util.controlledVocab.model.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * Class handling the update of CvObject.
 *
 * @author Samuel Kerrien (skerrien@ebi.ac.uk)
 * @version $Id$
 * @since <pre>16-Oct-2005</pre>
 */
public class UpdateCVs {

    // TODO Check the highest IntAct ID in the CV file and synchronize the sequence.
    //      eg, create a new sequence: id = 51
    //      load a file which has already IA:250
    //      when asking for a new id, it is very likely to have been already assigned.
    //      so pick the highest, say 250, and run the sequence up to that id.

    /////////////////////////////////
    // Update of the IntAct Data

    public static long searchLastIntactId( IntactOntology ontology ) {

        long max = 0;

        Collection cvTerms = ontology.getCvTerms();
        for ( Iterator iterator = cvTerms.iterator(); iterator.hasNext(); ) {
            CvTerm cvTerm = (CvTerm) iterator.next();

            final String prefix = "IA:";

            String id = cvTerm.getId();
            if ( id.startsWith( prefix ) ) {
                // found an intact id
                String value = id.substring( prefix.length(), id.length() );

                max = Math.max( max, Long.parseLong( value ) );
            }
        }

        return max;
    }

    /**
     * Global Update of the IntAct CVs, based upon an Ontology object. This is an iterative process that will update
     * each supported CVs independantely.
     *
     * @param ontology Controlled vocabularies upon which we do the update.
     *
     * @throws IntactException upon data access error
     */
    public static void update( IntactOntology ontology ) throws IntactException {

        // try to update class by class
        Collection allTypes = ontology.getTypes();
        for ( Iterator iterator = allTypes.iterator(); iterator.hasNext(); ) {
            Class aClass = (Class) iterator.next();

            // single class update
            update( ontology, aClass );
        }
    }

    private static String getShortClassName( Class clazz ) {

        return clazz.getName().substring( clazz.getName().lastIndexOf( '.' ) + 1,
                                          clazz.getName().length() );
    }

    /**
     * Update a specific supported Controlled Vocabulary.
     * <p/>
     * Note: The Ontology contains a mapping Class -> CV.
     *
     * @param ontology
     * @param cvObjectClass
     *
     * @throws IntactException
     */
    public static void update( IntactOntology ontology, Class cvObjectClass ) throws IntactException {

        System.out.println( "====================================================" );
        System.out.println( "Updating " + cvObjectClass.getName() );
        System.out.println( "====================================================" );

        /**
         * ALGO
         * ----
         *
         * Iterate over all new Terms (OBO)
         * for each
         *     check if we have it in IntAct (UpdateTerm consists in updating shortlabel, fullname,
         *                                                                    annotations, xrefs, aliases)
         *          Yes: updateTerm
         *          No:  create basic object, then updateTerm
         *
         * Iterate over all new Terms (OBO)
         * for each
         *     check children and apply hierarchy to IntAct
         */
        IntactHelper helper = null;
        try {
            helper = new IntactHelper();

            Collection intactTerms = helper.search( cvObjectClass, "ac", null );
            System.out.println( "\t " + intactTerms.size() + " term(s) found in IntAct." );

            ///////////////////////////////////////////////////////
            // 1. Indexing of the IntAct terms by MI number
            Map intactIndex = new HashMap( intactTerms.size() );
            for ( Iterator iterator = intactTerms.iterator(); iterator.hasNext(); ) {
                CvObject cvObject = (CvObject) iterator.next();
                String psi = getPsiId( cvObject );

                // Bear in mind that only CV that already exists are indexed here, newly created ones will be indexed later.
                if ( psi != null ) {
                    intactIndex.put( psi, cvObject );
                } else {
                    System.err.println( "Could not index ( '" + cvObject.getShortLabel() + "' doesn't have an MI ID)." );

                    // search for an IntAct id
                    String ia = getIntactId( cvObject );

                    if ( ia == null ) {

                        // TODO doing the IS:xxxx update here may cause a problem if the file we are loading
                        //      contains some IA:xxxx, they may clash.
                        //      It would be better to update all terms after the file has been loaded.

                        // add an IA:xxxx
                        System.out.println( "Could not find an IntAct id (IA:xxxx), add a new one." );
                        try {
                            ia = SequenceManager.getNextId( helper );

                            Xref xref = new Xref( helper.getInstitution(), intact, ia, null, null, identity );
                            cvObject.addXref( xref );
                            helper.create( xref );
                            System.out.println( "Added: " + xref + " to " );
                            System.out.println( "\t\t Created Xref( " + intact.getShortLabel() + ", " +
                                                identity.getShortLabel() + ", " + xref.getPrimaryId() + " ) (" + cvObject.getShortLabel() + ")" );

                        } catch ( Exception e ) {
                            System.out.println( "An error occured while add the IntAct id, see exception below:" );
                            Throwable t = (Throwable) e;
                            while ( t != null ) {
                                t.printStackTrace();
                                t = t.getCause();
                                if ( t != null ) {
                                    System.out.println( "======================= CAUSED BY =======================" );
                                }
                            }
                        } // catch
                    } // no IA:xxxx found
                } // no PSI id found
            } // for

            ////////////////////////////////////////
            // 2. update of the terms' content
            Collection oboTerms = ontology.getCvTerms( cvObjectClass );
            System.out.println( "\t " + oboTerms.size() + " term(s) loaded from definition file." );
            for ( Iterator iterator = oboTerms.iterator(); iterator.hasNext(); ) {
                CvTerm cvTerm = (CvTerm) iterator.next();
                CvObject cvObject = (CvObject) intactIndex.get( cvTerm.getId() );

                helper.startTransaction( BusinessConstants.JDBC_TX );
                System.out.println( "----------------------------------------------------------------------------------" );
                if ( cvObject == null ) {

                    // search by shortlabel
                    cvObject = (CvObject) helper.getObjectByLabel( cvObjectClass, cvTerm.getShortName() );

                    if ( cvObject == null ) {
                        // could not find it, hence create it.
                        try {
                            // create a new object using reflection
                            Constructor constructor =
                                    cvObjectClass.getConstructor( new Class[]{ Institution.class, String.class } );

                            cvObject = (CvObject)
                                    constructor.newInstance( new Object[]{ helper.getInstitution(), cvTerm.getShortName() } );
                        } catch ( Exception e ) {
                            e.printStackTrace();
                            continue;
                        }

                        // persist it
                        helper.create( cvObject );
                        String className = getShortClassName( cvObject.getClass() );
                        System.out.println( "\t Creating " + className + "( " + cvObject.getShortLabel() + " )" );

                        // add that new term in the index
                        intactIndex.put( cvTerm.getId(), cvObject );
                    }
                } else {
                    String className = getShortClassName( cvObject.getClass() );
                    System.out.println( "\t Updating existing " + className + "( " + cvObject.getShortLabel() + " )" );
                }

                // update its content
                try {
                    updateTerm( helper, cvObject, cvTerm );

                    // TODO update mapping ?

                    // end the transaction as no error occured.
                    helper.finishTransaction();
                } catch ( Throwable t ) {

                    if ( helper.isInTransaction() ) {
                        System.err.print( "Rolling back transaction ... " );
                        helper.undoTransaction();
                        System.err.println( "done" );
                    }

                    t.printStackTrace();
                }
            } // end of update of the terms' content

            ///////////////////////////////////////
            // 3. Update of the hierarchy
            System.out.println( "+++++++++++++++++++++++++++++++++++++++++" );
            System.out.println( "\t Updating Vocabulary hierarchy..." );
            boolean stopUpdate = false;
            for ( Iterator iterator = oboTerms.iterator(); iterator.hasNext() && !stopUpdate; ) {
                CvTerm cvTerm = (CvTerm) iterator.next();

                // Get the IntAct equivalent from the index (it should have been either created or updated)
                CvObject cvObject = (CvObject) intactIndex.get( cvTerm.getId() );

                if ( cvObject == null ) {
                    // that should never happen !! Exception
                    System.out.println( "ERROR - Could not find " + cvTerm.getId() + " - " + cvTerm.getShortName() + ". skipping term." );
                    continue;
                }

                // check that the term is a DAG (ie. CvDagObject), if not, skip the hierarchy update.
                CvDagObject dagObject = null;
                if ( cvObject instanceof CvDagObject ) {
                    dagObject = (CvDagObject) cvObject;
                } else {
                    // we do not have heterogeneous collection here, we can stop the hierarchy update here.
                    System.out.println( "ERROR - " + cvObject.getClass().getName() + " is not a DAG, skip hierarchy update." );
                    stopUpdate = true;
                    continue;
                }

                // keep in that collection the term that haven't been read yet from the obo definition
                Collection allChildren = new ArrayList( dagObject.getChildren() );

                // browse all direct children of the current term
                for ( Iterator iterator2 = cvTerm.getChildren().iterator(); iterator2.hasNext(); ) {
                    CvTerm child = (CvTerm) iterator2.next();

                    // get corresponding IntAct child
                    CvDagObject intactChild = (CvDagObject) intactIndex.get( child.getId() );

                    if ( intactChild == null ) {
                        System.out.println( "ERROR - Could not find Child term of " + cvTerm.getShortName() + "(" +
                                            cvTerm.getId() + ") in the index (" + child.getId() + ")." );
                        continue;
                    }

                    // if the relationship doesn't exists, create it
                    if ( ! dagObject.getChildren().contains( intactChild ) ) {
                        // add that relation
                        dagObject.addChild( intactChild );

                        System.out.println( "\t\t Adding Relationship[(" + dagObject.getAc() + ") " + dagObject.getShortLabel() +
                                            " ---child---> (" + intactChild.getAc() + ") " + intactChild.getShortLabel() + "]" );

                        // TODO may need to do at at SQL level
                        helper.update( dagObject );
                        helper.update( intactChild );
                    }

                    allChildren.remove( intactChild );
                }

                // delete all relationships that were not described in the OBO file.
                for ( Iterator iterator2 = allChildren.iterator(); iterator2.hasNext(); ) {
                    CvDagObject child = (CvDagObject) iterator2.next();

                    try {
                        Connection connection = helper.getJDBCConnection();
                        Statement statement = connection.createStatement();
                        statement.execute( "DELETE FROM ia_cv2cv " +
                                           "WHERE parent_ac = '" + dagObject.getAc() + "' AND " +
                                           "      child_ac = '" + child.getAc() + "'" );
                        statement.close();

                        // BUG: when a CvFeatureType is used in the data, and we try to delete some relationship,
                        //      OJB deletes the CvObject itself ... which causes a constraint violation on foreign key.
                        //      P6Spy allowed to debug that.
                        // WORK AROUND: do that data update at SQL level, dodgy but at least it works :(

//                    dagObject.removeChild( child );
//                    helper.update( dagObject );
//                    helper.update( child );

                        System.out.println( "\t\t Removing Relationship[(" + dagObject.getAc() + ") " + dagObject.getShortLabel() +
                                            " ---child---> (" + child.getAc() + ") " + child.getShortLabel() + "]" );

                    } catch ( SQLException e ) {
                        e.printStackTrace();
                    }
                }
            } // end of update of the hierarchy

            ///////////////////////////////////////
            // 4. Flag root term as hidden
            Collection roots = ontology.getRoots( cvObjectClass );
            CvTopic hidden = (CvTopic) getCvObject( helper, CvTopic.class, CvTopic.HIDDEN );

            if ( hidden == null ) {
                System.out.println( "WARNING: The CvTopic(" + CvTopic.HIDDEN + ") could not be found or created in IntAct. " +
                                    "Skip flagging of the root terms." );
            } else {
                for ( Iterator iterator = roots.iterator(); iterator.hasNext(); ) {
                    CvTerm rootTerm = (CvTerm) iterator.next();

                    // get the intact term
                    CvObject root = (CvObject) intactIndex.get( rootTerm.getId() );

                    if ( root == null ) {
                        System.out.println( "ERROR: the term " + rootTerm.getId() + " should have been found in IntAct." );
                    } else {
                        addUniqueAnnotation( helper, root, hidden, "Root term deprecated during manual curation as too unspecific." );
                    }
                }
            }
        } finally {
            if ( helper != null ) {
                helper.closeStore();
            }
        }
    }

    /**
     * Go through all obsolete OBO terms and list all terms that weren't in IntAct.
     * <p/>
     * These terms are not going to be included in IntAct as we cannot guess their type.
     *
     * @param ontology the ontology from which we will get the list of obsolete terms
     *
     * @throws IntactException if something goes wrong
     */
    public static void listOrphanObsoleteTerms( IntactOntology ontology ) throws IntactException {

        System.out.println( "++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++" );
        System.out.println( "Updating Obsolete Terms" );

        List missingTerms = new ArrayList();
        IntactHelper helper = null;

        try {
            helper = new IntactHelper();

            // note: we don't create term that weren't existing in IntAct before if they are already obsolete in PSI
            Collection obsoleteTerms = ontology.getObsoleteTerms();

            for ( Iterator iterator = obsoleteTerms.iterator(); iterator.hasNext(); ) {
                CvTerm cvTerm = (CvTerm) iterator.next();

                String id = cvTerm.getId();
                CvObject cvObject = (CvObject) helper.getObjectByPrimaryId( CvObject.class, id );
                if ( cvObject == null ) {
                    missingTerms.add( cvTerm );
                }
//            else {
                // NOTE: this should having been taken care of during the update of the CvTerm.
//                CvTopic obsolete = (CvTopic) getCvObject( helper, CvTopic.class, CvTopic.OBSOLETE );
                // add the annotation obsolete if the term is not obsolete itself.
//                if ( ! cvTerm.getId().equals( CvTopic.OBSOLETE_MI_REF ) ) {
//                    addUniqueAnnotation( helper, cvObject, obsolete, cvTerm.getObsoleteMessage() );
//                }
//            }
            }
        } finally {
            if ( helper != null ) {
                helper.closeStore();
            }
        }

        if ( ! missingTerms.isEmpty() ) {

            System.out.println( "---------------------------------------------------------------------------------------" );
            System.out.println( "WARNING - The list of terms below could not be added to your IntAct node" );
            System.out.println( "          Reason:   These terms are obsolete in PSI-MI and the ontology doesn't keep " );
            System.out.println( "                    track of the root of obsolete terms." );
            System.out.println( "          Solution: if you really want to add these terms into IntAct, you will have to " );
            System.out.println( "                    do it manually and make sure that they get their MI:xxxx." );
            System.out.println( "---------------------------------------------------------------------------------------" );
            for ( Iterator iterator = missingTerms.iterator(); iterator.hasNext(); ) {
                CvTerm cvTerm = (CvTerm) iterator.next();
                System.out.println( cvTerm.getId() + " - " + cvTerm.getShortName() );
            }
        }
    }

    /**
     * Add an annotation in a CvObject if it is not in there yet. The CvTopic and the text of the annotation are given
     * as parameters so the methods is flexible.
     *
     * @param helper   database access
     * @param cvObject the CvObject in which we want to add the annotation
     * @param topic    the topic of the annotation. must not be null.
     * @param text     the text of the annotation. Can be null.
     *
     * @throws IntactException if something goes wrong during the update.
     */
    private static void addUniqueAnnotation( final IntactHelper helper,
                                             final CvObject cvObject,
                                             final CvTopic topic,
                                             final String text ) throws IntactException {
        if ( topic == null ) {
            System.err.println( "ERROR - You must give a non null topic when updating term " + cvObject.getShortLabel() );
        } else {

            // We allow only one annotation to carry the given topic,
            //   > if one if found, we update the test,
            //   > if more than one, we delete the excess.

            // select all annotation of that object filtered by topic
            Collection annotationByTopic = new ArrayList();
            for ( Iterator iterator = cvObject.getAnnotations().iterator(); iterator.hasNext(); ) {
                Annotation annot = (Annotation) iterator.next();
                if ( topic.equals( annot.getCvTopic() ) ) {
                    annotationByTopic.add( annot );
                }
            }

            // update annotations
            if ( annotationByTopic.isEmpty() ) {

                // add a new one
                Annotation annotation = new Annotation( helper.getInstitution(), topic );
                annotation.setAnnotationText( text );
                helper.create( annotation );
                cvObject.addAnnotation( annotation );
                helper.update( cvObject );
                System.out.println( "Added Annotation " + topic.getShortLabel() + " to '" + cvObject.getShortLabel() + "'." );

            } else {

                // there's at least one annotation

                // first, check if the annotation we want to have in that CvObject is already in
                Annotation newAnnotation = new Annotation( helper.getInstitution(), topic );
                newAnnotation.setAnnotationText( text );

                if ( annotationByTopic.contains( newAnnotation ) ) {
                    // found it, then we just remove it from the list and we are done.
                    annotationByTopic.remove( newAnnotation );
                } else {
                    // not found, we recycle an existing annotation and delete all others
                    Iterator iterator = annotationByTopic.iterator();
                    Annotation annotation = (Annotation) iterator.next();
                    String oldText = annotation.getAnnotationText();
                    annotation.setAnnotationText( text );
                    helper.update( annotation );
                    System.out.println( "Updated " + cvObject.getShortLabel() + ", Annotation(" + topic.getShortLabel() + ")\n" +
                                        "        updated text from '" + oldText + "' to '" + text + "'." );

                    // remove it from the list as we are going to delete all other
                    iterator.remove();
                }
            }

            // if any annotation left, delete them as we want a unique one.
            for ( Iterator iterator = annotationByTopic.iterator(); iterator.hasNext(); ) {
                Annotation annotation = (Annotation) iterator.next();
                String _text = annotation.getAnnotationText();
                cvObject.removeAnnotation( annotation );
                helper.update( cvObject );
                helper.delete( annotation );
                System.out.println( "Deleted redondant Annotation(" + topic.getShortLabel() +
                                    ", '" + _text + "'), we want it unique and there's already one." );
            }

        } // topic is not null
    }

    /**
     * Browse the CvObject's Xref and find (if possible) the primary ID of the first Xref( CvDatabase( psi-mi ) ).
     *
     * @param cvObject the Object we are introspecting.
     *
     * @return a PSI ID or null is none is found.
     */
    private static String getPsiId( CvObject cvObject ) {

        for ( Iterator iterator = cvObject.getXrefs().iterator(); iterator.hasNext(); ) {
            Xref xref = (Xref) iterator.next();

            if ( psi.equals( xref.getCvDatabase() ) && identity.equals( xref.getCvXrefQualifier() ) ) {
                return xref.getPrimaryId();
            }
        }

        return null;
    }

    /**
     * Browse the CvObject's Xref and find (if possible) the primary ID of the first Xref( CvDatabase( intact ) ).
     *
     * @param cvObject the Object we are introspecting.
     *
     * @return a PSI ID or null is none is found.
     */
    private static String getIntactId( CvObject cvObject ) {

        for ( Iterator iterator = cvObject.getXrefs().iterator(); iterator.hasNext(); ) {
            Xref xref = (Xref) iterator.next();

            if ( intact.equals( xref.getCvDatabase() ) && identity.equals( xref.getCvXrefQualifier() ) ) {
                return xref.getPrimaryId();
            }
        }

        return null;
    }

    /**
     * Get the requested CvObject from the Database, create it if it doesn't exist. It is searched by shortlabel. If not
     * found, a CvObject is create using the information given (shortlabel)
     *
     * @param helper     access to the database
     * @param clazz      the CvObject concrete type
     * @param shortlabel shortlabel of the term
     *
     * @return a CvObject persistent in the backend.
     *
     * @throws IntactException          is an error occur while writting on the database.
     * @throws IllegalArgumentException if the class given is not a concrete type of CvObject (eg. CvDatabase)
     */
    public static CvObject getCvObject( IntactHelper helper,
                                        Class clazz,
                                        String shortlabel ) throws IntactException {
        return getCvObject( helper, clazz, shortlabel, null );
    }

    /**
     * Get the requested CvObject from the Database, create it if it doesn't exist. It is first searched by Xref(psi-mi)
     * if an ID is given, then by shortlabel. If not found, a CvObject is create using the information given
     * (shortlabel, then potentially a PSI ID)
     *
     * @param helper     access to the database
     * @param clazz      the CvObject concrete type
     * @param shortlabel shortlabel of the term
     * @param mi         MI id of the term
     *
     * @return a CvObject persistent in the backend.
     *
     * @throws IntactException          is an error occur while writting on the database.
     * @throws IllegalArgumentException if the class given is not a concrete type of CvObject (eg. CvDatabase)
     */
    public static CvObject getCvObject( IntactHelper helper,
                                        Class clazz,
                                        String shortlabel,
                                        String mi ) throws IntactException {

        // Check that the given class is a CvObject or one if its sub-type.
        if ( !CvObject.class.isAssignableFrom( clazz ) ) {
            throw new IllegalArgumentException( "The given class (" + getShortClassName( clazz ) + ") must be a sub type of CvObject" );
        }

        // Search by MI
        CvObject cv = null;

        // if an MI is available, search using it
        if ( mi != null ) {
            cv = (CvObject) helper.getObjectByPrimaryId( clazz, mi );
        }

        // if not found by MI, then search by shortlabel
        if ( cv == null ) {
            // Search by Name
            cv = (CvObject) helper.getObjectByLabel( clazz, shortlabel );
        }

        // if still not found, then create it.
        if ( cv == null ) {
            // create it
            try {

                // create a new object using refection
                Constructor constructor = clazz.getConstructor( new Class[]{ Institution.class, String.class } );
                cv = (CvObject) constructor.newInstance( new Object[]{ helper.getInstitution(), shortlabel } );

                // persist it
                helper.create( cv );
                System.out.println( "Created missing CV Term: " + getShortClassName( clazz ) + "( " + shortlabel + " )." );

                // create MI Xref if necessary
                if ( mi != null && mi.startsWith( "MI:" ) ) {

                    CvDatabase psi = null;
                    if ( mi.equals( CvDatabase.PSI_MI_MI_REF ) ) {
                        psi = (CvDatabase) cv;
                    } else {
                        psi = (CvDatabase) getCvObject( helper, CvDatabase.class,
                                                        CvDatabase.PSI_MI,
                                                        CvDatabase.PSI_MI_MI_REF );
                    }

                    CvXrefQualifier identity = null;
                    if ( mi.equals( CvXrefQualifier.IDENTITY_MI_REF ) ) {
                        identity = (CvXrefQualifier) cv;
                    } else {
                        identity = (CvXrefQualifier) getCvObject( helper, CvXrefQualifier.class,
                                                                  CvXrefQualifier.IDENTITY,
                                                                  CvXrefQualifier.IDENTITY_MI_REF );
                    }

                    Xref xref = new Xref( helper.getInstitution(), psi, mi, null, null, identity );

                    cv.addXref( xref );
                    helper.create( xref );
                    System.out.println( "Added required PSI Xref to " + shortlabel + ": " + mi );
                }
            } catch ( Exception e ) {
                // that's should not happen, but just in case...
                throw new IntactException( "Error while creating " + getShortClassName( clazz ) + "(" + shortlabel +
                                           ", " + mi + ").", e );
            }
        }

        return cv;
    }

    //////////////////////////////////////////////////
    // Management of unique Annotations and Xrefs

    /**
     * Select the list of topics that are unique in a CvObject.
     *
     * @param helper data access
     *
     * @return a non null Set of CvTopics. may be empty.
     *
     * @throws IntactException
     */
    private static Set loadUniqueCvTopics( IntactHelper helper ) throws IntactException {
        Set uniqueTopic = new HashSet();
        uniqueTopic.add( getCvObject( helper, CvTopic.class, CvTopic.DEFINITION ) );
        uniqueTopic.add( getCvObject( helper, CvTopic.class, CvTopic.OBSOLETE, CvTopic.OBSOLETE_MI_REF ) );
        uniqueTopic.add( getCvObject( helper, CvTopic.class, CvTopic.XREF_VALIDATION_REGEXP, CvTopic.XREF_VALIDATION_REGEXP_MI_REF ) );
        return uniqueTopic;
    }

    /**
     * Select the list of qualifiers that are unique in a CvObject.
     *
     * @param helper data access
     *
     * @return a non null Set of CvXrefQualifiers. may be empty.
     *
     * @throws IntactException
     */
    private static Set loadUniqueCvXrefQualifiers( IntactHelper helper ) throws IntactException {
        Set uniqueQualifier = new HashSet();
        uniqueQualifier.add( getCvObject( helper, CvXrefQualifier.class, CvXrefQualifier.PRIMARY_REFERENCE, CvXrefQualifier.PRIMARY_REFERENCE_MI_REF ) );
        uniqueQualifier.add( getCvObject( helper, CvXrefQualifier.class, CvXrefQualifier.IDENTITY, CvXrefQualifier.IDENTITY_MI_REF ) );
        return uniqueQualifier;
    }

    //////////////////////////////////
    // Filtering of Annotations/Xrefs

    /**
     * Selects all annotations from the given collection having the given topic.
     *
     * @param annotations a collection of annotation.
     * @param topic       the filter
     *
     * @return a non null collection of annotation. may be empty.
     */
    private static Collection select( Collection annotations, CvTopic topic ) {

        if ( annotations == null || annotations.isEmpty() ) {
            return Collections.EMPTY_LIST;
        }

        Collection selectedAnnotations = new ArrayList( annotations.size() );

        for ( Iterator iterator1 = annotations.iterator(); iterator1.hasNext(); ) {
            Annotation _annot = (Annotation) iterator1.next();
            if ( topic.equals( _annot.getCvTopic() ) ) {
                selectedAnnotations.add( _annot );
            }
        }

        return selectedAnnotations;
    }

    /**
     * Selects all Xrefs from the given collection having the given database and qualifier.
     *
     * @param xrefs     a collection of annotation.
     * @param database  the database filter
     * @param qualifier the qualifier filter
     *
     * @return a non null collection of xrefs. may be empty.
     */
    private static Collection select( Collection xrefs, CvDatabase database, CvXrefQualifier qualifier ) {

        if ( xrefs == null || xrefs.isEmpty() ) {
            return Collections.EMPTY_LIST;
        }

        Collection selectedXrefs = new ArrayList( xrefs.size() );

        for ( Iterator iterator1 = xrefs.iterator(); iterator1.hasNext(); ) {
            Xref xref = (Xref) iterator1.next();
            if ( database.equals( xref.getCvDatabase() ) ) {
                if ( qualifier.equals( xref.getCvXrefQualifier() ) ) {
                    selectedXrefs.add( xref );
                }
            }
        }

        return selectedXrefs;
    }

    /**
     * Selects all Xrefs from the given collection having the given database and primaryId.
     *
     * @param xrefs     a collection of annotation.
     * @param database  the database filter
     * @param primaryId the primaryId filter
     *
     * @return a non null collection of xrefs. may be empty.
     */
    private static Collection select( Collection xrefs, CvDatabase database, String primaryId ) {

        if ( xrefs == null || xrefs.isEmpty() ) {
            return Collections.EMPTY_LIST;
        }

        Collection selectedXrefs = new ArrayList( xrefs.size() );

        for ( Iterator iterator1 = xrefs.iterator(); iterator1.hasNext(); ) {
            Xref xref = (Xref) iterator1.next();
            if ( database.equals( xref.getCvDatabase() ) ) {
                if ( primaryId.equals( xref.getPrimaryId() ) ) {
                    selectedXrefs.add( xref );
                }
            }
        }

        return selectedXrefs;
    }

    /////////////////////////////////
    // Update procedures

    /**
     * Update an IntAct CV term based on the definition read externally, and contained in a CvTerm.
     *
     * @param helper   data access.
     * @param cvObject the IntAct CV to update.
     * @param cvTerm   the new definition of that CV.
     *
     * @throws IntactException if error occur.
     */
    private static void updateTerm( IntactHelper helper,
                                    CvObject cvObject,
                                    CvTerm cvTerm ) throws IntactException {

        // TODO unique items: Xref( database, identity ),
        // TODO               Xref( database, primary-reference ),
        // TODO               Annotation( definition )
        // TODO PsiLoader could make sure that there is only one of them in each term.

        /**
         * Data found in CV Terms:
         * Xref:
         *      pubmed                (1..n)
         *      resid                 (0..n)
         *      go                    (0..n)
         *      so                    (0..n)
         *
         * Annotation
         *      definition            (1)
         *      obsolete              (0..1)
         *      id-validation-regexp  (0..1)
         *      comment               (0..n)
         *      url                   (0..n)
         *
         * Alias
         *      go synonym            (0..n)
         */

        // we know they have the same ID (either IA:xxxx or MI:xxxx)
        // Note: in IntAct we keep both IA:xxxx and MI:xxxx to they remain searchable.
        String mi = getPsiId( cvObject );
        String ia = getIntactId( cvObject );
        boolean hasIntactTermGotPsiIdentifier = ( mi != null );
        boolean hasIntactTermGotIntactIdentifier = ( ia != null );

        String id = cvTerm.getId();
        boolean hasPsiIdentifier = id.startsWith( "MI:" );
        boolean hasIntactIdentifier = id.startsWith( "IA:" );

        System.out.println( "\t Updating CV: " + cvTerm.getShortName() + " (" + id + ")" );

        boolean needsUpdate = false;

        // shortname
        if ( !cvObject.getShortLabel().equals( cvTerm.getShortName() ) ) {
            cvObject.setShortLabel( cvTerm.getShortName() );
            needsUpdate = true;
            System.out.println( "\t\t Updated shortlabel (" + cvTerm.getShortName() + ")" );
        }

        // fullname
        if ( cvObject.getFullName() != null ) {
            if ( !cvObject.getFullName().equals( cvTerm.getFullName() ) ) {
                cvObject.setFullName( cvTerm.getFullName() );
                System.out.println( "\t\t Updated fullname (" + cvTerm.getShortName() + ")" );
                needsUpdate = true;
            }
        } else {
            // cvObject.getFullName() == null
            if ( cvTerm.getFullName() != null ) {
                cvObject.setFullName( cvTerm.getFullName() );
                needsUpdate = true;
                System.out.println( "\t\t Updated fullname (" + cvTerm.getShortName() + ")" );
            }
        }

        // TODO if we convert the id into a Xref before starting the update, the two if below would be handled in the loop for Xrefs.

        // Xref psi-mi/identity
        if ( ! hasIntactTermGotPsiIdentifier && hasPsiIdentifier ) {
            // the intact term doesn't have a PSI Xref although the CvTerm has one, add missing mi Xref.
            CvDatabase psi = (CvDatabase) getCvObject( helper, CvDatabase.class, CvDatabase.PSI_MI, CvDatabase.PSI_MI_MI_REF );
            CvXrefQualifier identity = (CvXrefQualifier) getCvObject( helper, CvXrefQualifier.class, CvXrefQualifier.IDENTITY, CvXrefQualifier.IDENTITY_MI_REF );

            Xref xref = new Xref( helper.getInstitution(), psi, id, null, null, identity );
            cvObject.addXref( xref );
            helper.create( xref );
            System.out.println( "\t\t Added PSI Xref (" + id + ")" );
        }

        if ( ! hasIntactTermGotIntactIdentifier && hasIntactIdentifier ) {
            // add missing ia Xref

            // Search for other terms having that specific IA:xxxx, if we find any, we give them an other one.
            // the IA:xxxx coming from the file has priority
            CvDatabase intact = (CvDatabase) getCvObject( helper, CvDatabase.class, CvDatabase.INTACT, CvDatabase.INTACT_MI_REF );
            CvXrefQualifier identity = (CvXrefQualifier) getCvObject( helper, CvXrefQualifier.class, CvXrefQualifier.IDENTITY, CvXrefQualifier.IDENTITY_MI_REF );

            Collection conflictingTerms = helper.getObjectsByXref( CvObject.class, intact, identity, id );
            for ( Iterator iterator = conflictingTerms.iterator(); iterator.hasNext(); ) {
                CvObject conflict = (CvObject) iterator.next();
                System.out.println( "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" );
                System.out.println( "WARNING: found a CV term using the same IntAct Xref: " + id + " replacing id..." );
                Collection xrefs = select( conflict.getXrefs(), intact, identity );
                String newId = SequenceManager.getNextId( helper );
                if ( ! xrefs.isEmpty() ) {
                    Iterator it = xrefs.iterator();
                    Xref xref = (Xref) it.next();
                    xref.setPrimaryId( newId );
                    helper.update( xref );
                    System.out.println( "Updated Xref (" + id + ") updated to " + newId + " on term '" + conflict.getShortLabel() + "'." );

                    while ( it.hasNext() ) {
                        xref = (Xref) it.next();
                        conflict.removeXref( xref );
                        helper.delete( xref );
                        helper.update( conflict );
                        System.out.println( "Deleted additional Xref:" + xref );
                    }
                } else {
                    System.out.println( "ERROR - no matching Xref found in that term." );
                }
            }

            Xref xref = new Xref( helper.getInstitution(), intact, id, null, null, identity );
            cvObject.addXref( xref );
            helper.create( xref );
            System.out.println( "\t\t Added IntAct Xref (" + id + ")" );
        }

        if ( needsUpdate ) {
            helper.update( cvObject );
        }

        // Annotations
        updateAnnotations( helper, cvObject, cvTerm );

        // Xrefs
        updateXrefs( helper, cvObject, cvTerm );

        // Aliases
        updateAliases( helper, cvObject, cvTerm );
    }

    /**
     * Update Annotation of the given CvObject using the cvTerm definition read from the psi ontology file.
     *
     * @param helper   data access to the database.
     * @param cvObject the cvObject we want to update.
     * @param cvTerm   the cvTerm containing the data to update from.
     *
     * @throws IntactException if an error occurs during the update.
     */
    private static void updateAnnotations( IntactHelper helper, CvObject cvObject, CvTerm cvTerm ) throws IntactException {

        // build a copy of the annotation list and add obsolete and definition (if any).
        List annotations = new ArrayList( cvTerm.getAnnotations() );

        if ( ! cvTerm.getId().equals( CvTopic.OBSOLETE_MI_REF ) ) {
            // the Obsolete term is not Obsolete in IntAct.
            if ( cvTerm.isObsolete() || cvTerm.getObsoleteMessage() != null ) {
                annotations.add( new CvTermAnnotation( CvTopic.OBSOLETE, cvTerm.getObsoleteMessage() ) );
            }
        }

        // Definition is to be stored as an Annotation in IntAct.
        // If any available in the PSI term, reflect that in the CvTerm before to start to update.
        String def = cvTerm.getDefinition();
        if ( def != null && def.length() > 0 ) {
            CvTermAnnotation annot = new CvTermAnnotation( CvTopic.DEFINITION, cvTerm.getDefinition() );
            annotations.add( annot );
        }

        Set uniqueCvTopics = loadUniqueCvTopics( helper );

        // Start updating ...
        for ( Iterator iterator = annotations.iterator(); iterator.hasNext(); ) {
            CvTermAnnotation annotation = (CvTermAnnotation) iterator.next();

            // the term will be created if it doesn't exist yet.
            CvTopic topic = (CvTopic) getCvObject( helper, CvTopic.class, annotation.getTopic() );

            if ( topic != null ) {

                if ( uniqueCvTopics.contains( topic ) ) {

                    // only one instance of that topic allowed.
                    Collection annotationsByTopic = select( cvObject.getAnnotations(), topic );
                    System.out.println( "\t\t Select from Annotation with topic '" + topic.getShortLabel() + "' returned " + annotationsByTopic.size() + " hit(s)." );

                    if ( annotationsByTopic.isEmpty() ) {
                        // create one
                        Annotation annot = new Annotation( helper.getInstitution(), topic, annotation.getAnnotation() );

                        helper.create( annot );

                        cvObject.addAnnotation( annot );
                        helper.update( cvObject );
                        System.out.println( "\t\t Created unique Annotation( " + topic.getShortLabel() + ", '" + annot.getAnnotationText() + "' )" );

                    } else {

                        Iterator i = annotationsByTopic.iterator();
                        Annotation annot = (Annotation) i.next();

                        // update that one if the text is different.
                        String text = ( annot.getAnnotationText() == null ? "" : annot.getAnnotationText() );
                        String newtext = ( annotation.getAnnotation() == null ? "" : annotation.getAnnotation() );

                        if ( ! text.equals( newtext ) ) {
                            // only update if required.
                            annot.setAnnotationText( annotation.getAnnotation() );
                            helper.update( annot );
                            System.out.println( "\t\t Updated Annotation( " + topic.getShortLabel() + ", '" + annot.getAnnotationText() + "' )" );
                        }

                        // delete all remaining ones as we want to maintain a unique annotation
                        while ( i.hasNext() ) {
                            annot = (Annotation) i.next();

                            System.out.println( "\t\t Removed Annotation( " + topic.getShortLabel() + ", '" + annot.getAnnotationText() + "' )" );
                            cvObject.removeAnnotation( annot );
                            helper.update( cvObject );
                            helper.delete( annot );
                        }
                    } // end - at least one annotation

                } else {

                    // more than one instance of that topic allowed.
                    Annotation annot = new Annotation( helper.getInstitution(), topic, annotation.getAnnotation() );

                    if ( ! cvObject.getAnnotations().contains( annot ) ) {
                        // add missing annotation

                        helper.create( annot );

                        cvObject.addAnnotation( annot );
                        helper.update( cvObject );
                        System.out.println( "\t\t Created Annotation( " + topic.getShortLabel() + ", '" + annot.getAnnotationText() + "' ). <topic not unique>" );
                    }
                }
            } else {
                System.out.println( "ERROR - could not find or create CvTopic( " + annotation.getTopic() + " ) in IntAct. Skip annotation." );
            }
        }
    }

    /**
     * Update Xrefs of the given CvObject using the cvTerm definition read from the psi ontology file.
     *
     * @param helper   data access to the database.
     * @param cvObject the cvObject we want to update.
     * @param cvTerm   the cvTerm containing the data to update from.
     *
     * @throws IntactException if an error occurs during the update.
     */
    private static void updateXrefs( IntactHelper helper, CvObject cvObject, CvTerm cvTerm ) throws IntactException {

        // Database Mapping PSI to IntAct
        Map dbMapping = new HashMap();
        dbMapping.put( "PMID", CvDatabase.PUBMED );
        dbMapping.put( "RESID", CvDatabase.RESID );
        dbMapping.put( "SO", CvDatabase.SO );
        dbMapping.put( "GO", CvDatabase.GO );

        // xrefs -- we don't delete any Xrefs, just adding missing ones
        // TODO should we affect a default qualifier if none is supplied ?
//        CvXrefQualifier goDefinitionRef = (CvXrefQualifier) getCvObject( helper, CvXrefQualifier.class,
//                                                                         CvXrefQualifier.GO_DEFINITION_REF,
//                                                                         CvXrefQualifier.GO_DEFINITION_REF_MI_REF );
        Set uniqueQualifiers = loadUniqueCvXrefQualifiers( helper );

        for ( Iterator iterator = cvTerm.getXrefs().iterator(); iterator.hasNext(); ) {
            CvTermXref cvTermXref = (CvTermXref) iterator.next();

            CvDatabase database = (CvDatabase) getCvObject( helper, CvDatabase.class, cvTermXref.getDatabase() );
            CvXrefQualifier qualifier = (CvXrefQualifier) getCvObject( helper, CvXrefQualifier.class, cvTermXref.getQualifier() );

            Xref newXref = new Xref( helper.getInstitution(), database, cvTermXref.getId(), null, null, qualifier );

            if ( cvObject.getXrefs().contains( newXref ) ) {
                // found it, skip.
                continue;
            }

            boolean updated = false;

            Collection xrefs = select( cvObject.getXrefs(), database, cvTermXref.getId() );
            if ( ! xrefs.isEmpty() ) {
                // there's at least one Xref matching that Xref( db, id )
                System.out.println( "\t\tFound " + xrefs.size() + " Xref" + ( xrefs.size() > 1 ? "s" : "" ) + " having database(" +
                                    database.getShortLabel() + ") and id(" + cvTermXref.getId() + ")." );

                // note: Xref( db, id ) must be unique.
                // 1. update qualifier
                // 2. delete all others
                // 3. go to the next Xref

                // update first one to the new qualifier
                Iterator itx = xrefs.iterator();
                Xref xref = (Xref) itx.next();
                CvXrefQualifier old = xref.getCvXrefQualifier();
                xref.setCvXrefQualifier( qualifier );
                helper.update( xref );
                updated = true;

                System.out.println( "\t\tUpdated (" + xref.getAc() + ") Xref's qualifier from " + old.getShortLabel() + " to " + qualifier.getShortLabel() );

                if ( itx.hasNext() ) {
                    System.out.println( "\t\t Deleting Xrefs having the same Database / ID as only one should be there:" );
                }
                while ( itx.hasNext() ) {
                    xref = (Xref) itx.next();
                    cvObject.removeXref( xref );
                    helper.delete( xref );
                    System.out.println( "\t\t\t Xref( " + database.getShortLabel() + ", " +
                                        xref.getCvXrefQualifier().getShortLabel() + ", " + xref.getPrimaryId() +
                                        " ) (" + cvTerm.getShortName() + ")" );
                }

            } else {

                // No match using db / id.

                if ( uniqueQualifiers.contains( qualifier ) ) {

                    // Now try filtering with Xref( db, qualifier )
                    Collection selectedXrefs = select( cvObject.getXrefs(), database, qualifier );

                    // here we may select an Xref that has just been updated in the block above when selecting by db/id.

                    if ( selectedXrefs.isEmpty() ) {
                        // create missing Xref
                        cvObject.addXref( newXref );
                        helper.create( newXref );
                        System.out.println( "\t\t Created Xref( " + database.getShortLabel() + ", " + qualifier.getShortLabel() + ", " + newXref.getPrimaryId() + " ) (" + cvTerm.getShortName() + ")" );

                    } else {
                        // update the first one
                        Iterator itXrefs = null;
                        if ( updated ) {
                            // remove the Xref and let the others being deleted.
                            selectedXrefs.remove( newXref );
                            itXrefs = selectedXrefs.iterator();
                        } else {
                            // no update yet - update the primaryId
                            itXrefs = selectedXrefs.iterator();
                            Xref xref = (Xref) itXrefs.next();
                            if ( ! xref.equals( newXref ) ) {
                                xref.setPrimaryId( cvTermXref.getId() );
                                helper.update( xref );
                                System.out.println( "\t\t Updated Xref( " + database.getShortLabel() + ", " + qualifier.getShortLabel() + ", " + xref.getPrimaryId() + " ) (" + cvTerm.getShortName() + ")" );
                            }
                        }

                        // delete all remaining xrefs
                        while ( itXrefs.hasNext() ) {
                            Xref xref = (Xref) itXrefs.next();
                            helper.delete( xref );
                            System.out.println( "\t\t Deleted Xref( " + database.getShortLabel() + ", " + qualifier.getShortLabel() + ", " + xref.getPrimaryId() + " ) (" + cvTerm.getShortName() + ")" );
                        }
                    }

                } else {
                    // That Xref can have multiple instances
                    if ( ! cvObject.getXrefs().contains( newXref ) ) {
                        cvObject.addXref( newXref );
                        helper.create( newXref );
                        System.out.println( "\t\t Created Xref( " + database.getShortLabel() + ", " + qualifier.getShortLabel() + ", " + newXref.getPrimaryId() + " ) (" + cvTerm.getShortName() + ")" );
                    }
                }
            }
        }
    }

    /**
     * Update Aliases of the given CvObject using the cvTerm definition read from the psi ontology file.
     *
     * @param helper   data access to the database.
     * @param cvObject the cvObject we want to update.
     * @param cvTerm   the cvTerm containing the data to update from.
     *
     * @throws IntactException if an error occurs during the update.
     */
    private static void updateAliases( IntactHelper helper, CvObject cvObject, CvTerm cvTerm ) throws IntactException {

        CvAliasType defaultAliasType = (CvAliasType) getCvObject( helper, CvAliasType.class, CvAliasType.GO_SYNONYM,
                                                                  CvAliasType.GO_SYNONYM_MI_REF );

        if ( defaultAliasType == null ) {
            throw new IllegalStateException( "Could not find " + CvAliasType.GO_SYNONYM + " in the IntAct node. Abort." );
        }

        for ( Iterator iterator = cvTerm.getSynonyms().iterator(); iterator.hasNext(); ) {
            CvTermSynonym synonym = (CvTermSynonym) iterator.next();

            CvAliasType specificType = null;

            if ( synonym.hasType() ) {
                // if the synonym has a type, we use it instead of the default go-synonym.
                specificType = (CvAliasType) getCvObject( helper, CvAliasType.class, synonym.getType() );
                if ( specificType == null ) {
                    System.out.println( "ERROR - Could not find or create CvAliasType( '" + synonym.getType() + "' ). skip Alias update." );
                    System.out.println( "ERROR - Use '" + defaultAliasType.getShortLabel() + "' instead." );
                    specificType = defaultAliasType;
                }
            }

            Alias alias = new Alias( helper.getInstitution(), cvObject, specificType, synonym.getName() );

            if ( ! alias.getName().equals( synonym.getName() ) ) {
                // the synonym was truncated, we don't import these.
                System.out.println( "\t\t Skipping Alias( " + specificType.getShortLabel() + ", '" +
                                    synonym.getName() + "' ) ... the content would be truncated." );
                continue;
            }

            if ( ! cvObject.getAliases().contains( alias ) ) {
                cvObject.addAlias( alias );
                helper.create( alias );
                System.out.println( "\t\t Created Alias( " + specificType.getShortLabel() + ", '" + synonym.getName() + "' )" );
            }
        }
    }

    /**
     * search for the first Annotations having the given CvTopic and deletes all others.
     *
     * @param helper   database access
     * @param cvObject the object on which we search for an annotation
     *
     * @return a unique annotation or null if none is found.
     *
     * @throws IntactException
     */
    private static Annotation getUniqueAnnotation( IntactHelper helper,
                                                   CvObject cvObject,
                                                   CvTopic topicFilter ) throws IntactException {
        if ( topicFilter == null ) {
            throw new NullPointerException();
        }

        Annotation myAnnotation = null;

        Collection<Annotation> toDelete = new ArrayList<Annotation>();

        for ( Iterator iterator = cvObject.getAnnotations().iterator(); iterator.hasNext(); ) {
            Annotation annotation = (Annotation) iterator.next();

            if ( topicFilter.equals( annotation.getCvTopic() ) ) {

                if ( myAnnotation == null ) {
                    myAnnotation = annotation; // we keep the first one and delete all others
                } else {
                    toDelete.add( annotation ); // keep track for later deletion
                }
            }
        } // for all annotations


        for ( Annotation annotation : toDelete ) {
            System.out.println( "Removing extra annotation: Annotation(" + annotation.getCvTopic().getShortLabel() + ", '" +
                                annotation.getAnnotationText() + "')" );
            cvObject.removeAnnotation( annotation );
            helper.update( cvObject );
            helper.delete( annotation );
        }

        return myAnnotation;
    }

    private static CvDatabase psi;
    private static CvDatabase intact;
    private static CvXrefQualifier identity;

    /**
     * Assures that necessary Controlled vocabulary terms are present prior to manipulation of other terms.
     *
     * @param helper access to the database.
     *
     * @throws uk.ac.ebi.intact.business.IntactException
     *
     */
    public static void createNecessaryCvTerms( IntactHelper helper ) throws IntactException {

        // Note, these object are being created is they don't exist yet. They are part
        // of psi-mi so they will be updated later.

        // CvXrefQualifier( identity )
        identity = (CvXrefQualifier) getCvObject( helper, CvXrefQualifier.class, CvXrefQualifier.IDENTITY, CvXrefQualifier.IDENTITY_MI_REF );

        // CvDatabase( psi-mi )
        psi = (CvDatabase) getCvObject( helper, CvDatabase.class, CvDatabase.PSI_MI, CvDatabase.PSI_MI_MI_REF );

        // CvDatabase( psi-mi )
        intact = (CvDatabase) getCvObject( helper, CvDatabase.class, CvDatabase.INTACT, CvDatabase.INTACT_MI_REF );

        // CvDatabase( pubmed )
        getCvObject( helper, CvDatabase.class, CvDatabase.PUBMED, CvDatabase.PUBMED_MI_REF );

        // CvDatabase( go )
        getCvObject( helper, CvDatabase.class, CvDatabase.GO, CvDatabase.GO_MI_REF );

        // CvDatabase( so )
        getCvObject( helper, CvDatabase.class, CvDatabase.SO, CvDatabase.SO_MI_REF );

        // CvDatabase( resid )
        getCvObject( helper, CvDatabase.class, CvDatabase.RESID, CvDatabase.RESID_MI_REF );

        // CvXrefQualifier( go-definition-ref )
        getCvObject( helper, CvXrefQualifier.class, CvXrefQualifier.GO_DEFINITION_REF, CvXrefQualifier.GO_DEFINITION_REF_MI_REF );

        // CvXrefQualifier( see-also )
        getCvObject( helper, CvXrefQualifier.class, CvXrefQualifier.SEE_ALSO, CvXrefQualifier.SEE_ALSO_MI_REF );

        // CvAliasType( go synonym )
        getCvObject( helper, CvAliasType.class, CvAliasType.GO_SYNONYM, CvAliasType.GO_SYNONYM_MI_REF );

        // CvTopic( comment )
        getCvObject( helper, CvTopic.class, CvTopic.COMMENT, CvTopic.COMMENT_MI_REF );

        // CvTopic( obsolete )
        getCvObject( helper, CvTopic.class, CvTopic.OBSOLETE, CvTopic.OBSOLETE_MI_REF );
    }

    /**
     * Reads a file and update CvObject's annotations accordingly.
     * <p/>
     * File format:
     * <pre>
     * shortlabel &lt;tab&gt; fullname &lt;tab&gt; type &lt;tab&gt; mi &lt;tab&gt; topic &lt;tab&gt; text &lt;tab&gt;
     * apply to children (true|false)
     * </pre>
     *
     * @param file the file containing the annotations.
     *
     * @throws IntactException if an error occurs during update.
     * @throws IOException     if an error occurs while handling the file.
     */
    private static void updateAnnotationsFromFile( File file ) throws IntactException, IOException {

        if ( file == null ) {
            throw new NullPointerException( "You must give a non NULL file" );
        }

        if ( ! file.exists() ) {
            throw new IllegalArgumentException( "The given file doesn't exist. Abort." );
        }

        if ( ! file.canRead() ) {
            throw new IllegalArgumentException( "The given file could not be read. Abort." );
        }

        System.out.println( "Updating CVs' annotations using: " + file.getAbsolutePath() );

        IntactHelper helper = null;
        BufferedReader in = null;

        try {
            helper = new IntactHelper();
            System.out.println( "Database: " + helper.getDbName() );

            in = new BufferedReader( new FileReader( file ) );
            String line;
            int lineCount = 0;
            while ( ( line = in.readLine() ) != null ) {

                lineCount++;
                line = line.trim();

                // skip comments
                if ( line.startsWith( "#" ) ) {
                    continue;
                }

                // skip empty lines
                if ( line.length() == 0 ) {
                    continue;
                }

                // process line
                StringTokenizer stringTokenizer = new StringTokenizer( line, "\t" );

                final String shorltabel = stringTokenizer.nextToken();           // 1. shortlabel
                final String fullname = stringTokenizer.nextToken();             // 2. fullname
                final String type = stringTokenizer.nextToken();                 // 3. type
                final String mi = stringTokenizer.nextToken();                   // 4. mi
                final String topic = stringTokenizer.nextToken();                // 5. topic
                final String reason = stringTokenizer.nextToken();               // 6. exclusion reason
                final String applyToChildrenValue = stringTokenizer.nextToken(); // 7. apply to children

                try {
                    final Class clazz = Class.forName( type );

                    boolean applyToChildren = false;
                    if ( "true".equalsIgnoreCase( applyToChildrenValue.trim() ) ) {
                        applyToChildren = true;
                    }

                    // find the CvObject
                    CvObject cv = null;
                    if ( mi != null && mi.startsWith( "MI:" ) ) {
                        cv = (CvObject) helper.getObjectByPrimaryId( clazz, mi );
                        if ( cv == null ) {
                            System.out.println( "Could not find the object by the given reference: '" + mi + "'." );
                        }
                    }

                    if ( cv == null ) {
                        // wasn't found using MI reference, then try shortlabel
                        if ( shorltabel != null && shorltabel.trim().length() > 0 ) {
                            cv = (CvObject) helper.getObjectByLabel( clazz, shorltabel );
                        } else {
                            throw new Exception( "Line " + lineCount + ": Neither a valid shortlabel (" + shorltabel + ") " +
                                                 "nor MI ref (" + mi + ") were given, could not find the corresponding " +
                                                 "CvObject. Skip line." );
                        }
                    }

                    if ( cv != null ) {
                        System.out.println( "-------------------------------------------------------------------------" );
                        System.out.println( "Read line " + lineCount + ": " + cv.getShortLabel() + "..." );

                        // if childrenToApply is true and the term is not a CvDagObject, skip and report error
                        if ( applyToChildren ) {
                            if ( ! CvDagObject.class.isAssignableFrom( cv.getClass() ) ) {
                                // error, CvObject that is not CvDagObject doesn't have children terms.
                                applyToChildren = false;
                                System.out.println( "Line " + lineCount + ": The specified type (" + cv.getClass() + ") is " +
                                                    "not hierarchical, though you have requested an updated on children " +
                                                    "term. set not to apply to children." );
                            }
                        }

                        // we have the object, now build the annotation
                        CvTopic cvTopic = (CvTopic) helper.getObjectByLabel( CvTopic.class, topic );
                        if ( cvTopic == null ) {
                            throw new Exception( "Line " + lineCount + ": Could not find CvTopic( " + topic + " ). Skip line." );
                        }

                        Set<CvObject> termsToUpdate = new HashSet<CvObject>();

                        // add the term itself
                        termsToUpdate.add( cv );

                        // if requested, its children
                        if ( applyToChildren ) {
                            // traverse the sub DAG and fill up the collection
                            collectAllChildren( (CvDagObject) cv, termsToUpdate );
                        }

                        // start the update the selected collection of CVs
                        for ( CvObject aTermToUpdate : termsToUpdate ) {

                            String termMi = getPsiId( aTermToUpdate );
                            if ( cv.equals( aTermToUpdate ) ) {
                                System.out.println( "Updating term: " + aTermToUpdate.getShortLabel() + " (" + termMi + ")" );
                            } else {
                                System.out.println( "Updating child: " + aTermToUpdate.getShortLabel() + " (" + termMi + ")" );
                            }
                            // now update that single term
                            Annotation annot = getUniqueAnnotation( helper, aTermToUpdate, cvTopic );


                            Annotation newAnnotation = new Annotation( helper.getInstitution(), cvTopic, reason );
                            if ( annot == null ) {
                                // then add the new one
                                helper.create( newAnnotation );
                                aTermToUpdate.addAnnotation( newAnnotation );
                                helper.update( aTermToUpdate );
                                System.out.println( "\tCREATED new Annotation( " + cvTopic.getShortLabel() + ", '" + reason + "' )" );

                            } else {

                                // then try to update it
                                if ( ! newAnnotation.equals( annot ) ) {

                                    System.out.println( "\tOLD: " + annot );
                                    System.out.println( "\tNEW: " + newAnnotation );

                                    // do the update.
                                    annot.setAnnotationText( reason );
                                    helper.update( annot );
                                    String myClassName = type.substring( type.lastIndexOf( "." ) + 1, type.length() );
                                    System.out.println( "\tUPDATED Annotation( " + cvTopic.getShortLabel() + ", '" + reason + "' )" );
                                }
                            }
                        } // update terms
                    } // if cv found

                } catch ( ClassNotFoundException e ) {

                    System.err.println( "Line " + lineCount + ": Object Type not supported: '" + type + "'. skipping." );

                } catch ( Exception e ) {

                    e.printStackTrace();
                }

            } // while - reading line by line

        } finally {
            if ( in != null ) {
                in.close(); // close() calls close() on encapsulated Reader.
            }

            if ( helper != null ) {
                helper.closeStore();
            }
        }
    }

    /**
     * Recursive methods that collects all children terms of the given terms.
     *
     * @param cv
     * @param termsToUpdate
     */
    private static void collectAllChildren( CvDagObject cv, Collection termsToUpdate ) {

        if ( termsToUpdate == null ) {
            throw new IllegalArgumentException( "You must give a non null collection." );
        }

        // note: if no children in the collection, then there is no recursive call ;)
        for ( Iterator iterator = cv.getChildren().iterator(); iterator.hasNext(); ) {
            CvDagObject child = (CvDagObject) iterator.next();
            termsToUpdate.add( child );
            collectAllChildren( child, termsToUpdate );
        }
    }

    //////////////////////////
    // M A I N

    public static void main( String[] args ) throws Exception {

        if ( args.length != 1 && args.length != 2 ) {
            System.err.println( "Usage: UpdateCVs <obo file> [<annotation update file>]" );
            System.exit( 1 );
        }

        String oboFilename = args[ 0 ];
        String annotFilename = null;
        if ( args.length == 2 ) {
            annotFilename = args[ 1 ];
        }

        /////////////////
        // 1. Parsing

        PSILoader psi = new PSILoader();
        IntactOntology ontology = psi.parseOboFile( new File( oboFilename ) );
        ontology.print();

        System.out.println( "====================================================================" );

        ////////////////////
        // 2. Updating

        // 2.1 Connect to the database.
        IntactHelper helper = new IntactHelper();
        String instanceName = helper.getDbName();
        System.out.println( "Database: " + instanceName );
        System.out.println( "User: " + helper.getDbUserName() );

        /////////////////////////////
        // 2.2 Checking on sequence

        long max = searchLastIntactId( ontology );
        SequenceManager.synchronizeUpTo( helper, max );

//        // 2.4 Check that we don't touch a production instance.
//        if ( instanceName.equalsIgnoreCase( "ZPRO" ) || instanceName.equalsIgnoreCase( "IWEB" ) ) {
//            helper.closeStore();
//            System.err.println( "This is an alpha version, you cannot edit " + instanceName + ". abort." );
//            System.exit( 1 );
//        }

        // 2.4 Create required vocabulary terms
        createNecessaryCvTerms( helper );
        helper.closeStore();

        // 2.5 update the CVs
        update( ontology );

        // 2.6 Update obsolete terms
        listOrphanObsoleteTerms( ontology );

        if ( annotFilename != null ) {
            try {
                updateAnnotationsFromFile( new File( annotFilename ) );
            } catch ( IOException e ) {
                System.err.println( "Could not Update CVs' annotations." );
                e.printStackTrace();
            }
        }
    }
}