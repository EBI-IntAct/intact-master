/*
Copyright (c) 2002 The European Bioinformatics Institute, and others.
All rights reserved. Please see the file LICENSE
in the root directory of this distribution.
*/
package uk.ac.ebi.intact.model;

import javax.persistence.Entity;
import javax.persistence.DiscriminatorValue;


/**
 * TODO comments
 *
 * @author hhe
 * @version $Id$
 */
@Entity
@DiscriminatorValue("uk.ac.ebi.intact.model.CvFeatureIdentification")
public class CvFeatureIdentification extends CvDagObject {


    public static final String X_RAY = "x-ray";
    public static final String X_RAY_MI_REF = "MI:0114";

    /**
     * Cache a Vector of all shortLabels of the class, e.g. for menus.
     * This should not be here as it has no model functionality but is
     * related to eg user interfaces.
     */
//    protected static Vector menuList = null;

    /**
     * This constructor should <b>not</b> be used as it could
     * result in objects with invalid state. It is here for object mapping
     * purposes only and if possible will be made private.
     * @deprecated Use the full constructor instead
     */
    @Deprecated
    public CvFeatureIdentification() {
        //super call sets creation time data
        super();
    }

    /**
     * Creates a valid CvFeatureIdentification instance. Requires at least a shortLabel and an
     * owner to be specified.
     * @param shortLabel The memorable label to identify this CvFeatureIdentification
     * @param owner The Institution which owns this CvFeatureIdentification
     * @exception NullPointerException thrown if either parameters are not specified
     */
    public CvFeatureIdentification(Institution owner, String shortLabel) {

        //super call sets up a valid CvObject
        super(owner, shortLabel);
    }

} // end CvFeatureIdentification



