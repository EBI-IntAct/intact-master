// Copyright (c) 2002-2003 The European Bioinformatics Institute, and others.
// All rights reserved. Please see the file LICENSE
// in the root directory of this distribution.

package uk.ac.ebi.intact.application.dataConversion.psiDownload.xmlGenerator;

import uk.ac.ebi.intact.application.dataConversion.PsiVersion;
import uk.ac.ebi.intact.application.dataConversion.psiDownload.UserSessionDownload;
import uk.ac.ebi.intact.application.dataConversion.psiDownload.xmlGenerator.psi1.Feature2xmlPSI1;
import uk.ac.ebi.intact.application.dataConversion.psiDownload.xmlGenerator.psi2.Feature2xmlPSI2;
import uk.ac.ebi.intact.application.dataConversion.psiDownload.xmlGenerator.psi25.Feature2xmlPSI25;

/**
 * Return an implenentation of Feature2xmlI according to the specified PSI version.
 *
 * @author Samuel Kerrien (skerrien@ebi.ac.uk)
 * @version $Id$
 */
public class Feature2xmlFactory {

    /**
     * Gives the right version of the Psi XML generator according to the user's session
     *
     * @param session the user session that will indicate which version of the Psi generator is required
     *
     * @return
     */
    public static Feature2xmlI getInstance( UserSessionDownload session ) {

        if ( session.getPsiVersion().equals( PsiVersion.VERSION_1 ) ) {

            return Feature2xmlPSI1.getInstance();

        } else if ( session.getPsiVersion().equals( PsiVersion.VERSION_2 ) ) {

            return Feature2xmlPSI2.getInstance();

        } else if ( session.getPsiVersion().equals( PsiVersion.VERSION_25 ) ) {

            return Feature2xmlPSI25.getInstance();

        } else {

            throw new IllegalStateException( "We do not support PSI version " + session.getPsiVersion() );
        }
    }
}