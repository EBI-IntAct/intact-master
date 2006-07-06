// Copyright (c) 2002-2003 The European Bioinformatics Institute, and others.
// All rights reserved. Please see the file LICENSE
// in the root directory of this distribution.

package uk.ac.ebi.intact.application.dataConversion.psiDownload.xmlGenerator.test;

import junit.framework.Test;
import junit.framework.TestSuite;
import uk.ac.ebi.intact.application.dataConversion.PsiVersion;
import uk.ac.ebi.intact.application.dataConversion.psiDownload.UserSessionDownload;
import uk.ac.ebi.intact.application.dataConversion.psiDownload.test.PsiDownloadTest;
import uk.ac.ebi.intact.application.dataConversion.psiDownload.xmlGenerator.Interaction2xmlFactory;
import uk.ac.ebi.intact.application.dataConversion.psiDownload.xmlGenerator.Interaction2xmlI;
import uk.ac.ebi.intact.application.dataConversion.psiDownload.xmlGenerator.psi1.Interaction2xmlPSI1;
import uk.ac.ebi.intact.application.dataConversion.psiDownload.xmlGenerator.psi2.Interaction2xmlPSI2;
import uk.ac.ebi.intact.application.dataConversion.psiDownload.xmlGenerator.psi25.Interaction2xmlPSI25;

/**
 * Test the behaviour of the Interaction2xmlFactory
 *
 * @author Samuel Kerrien (skerrien@ebi.ac.uk)
 * @version $Id$
 */
public class Interaction2xmlFactoryTest extends PsiDownloadTest {

    /**
     * Returns this test suite. Reflection is used here to add all the testXXX() methods to the suite.
     */
    public static Test suite() {
        return new TestSuite( Interaction2xmlFactoryTest.class );
    }

    ////////////////////////
    // Tests

    public void testGetInstance() {

        UserSessionDownload session = null;

        try {
            Interaction2xmlFactory.getInstance( session );
            fail( "You should no be allowed to give null to a Factory." );
        } catch ( Exception e ) {
            // ok
        }
    }

    public void testGetInstancePsi1() {

        UserSessionDownload session = new UserSessionDownload( PsiVersion.getVersion1() );

        Interaction2xmlI bsi = Interaction2xmlFactory.getInstance( session );

        assertNotNull( bsi );
        assertTrue( bsi instanceof Interaction2xmlPSI1 );
    }

    public void testGetInstancePsi2() {

        UserSessionDownload session = new UserSessionDownload( PsiVersion.getVersion2() );

        Interaction2xmlI bsi = Interaction2xmlFactory.getInstance( session );

        assertNotNull( bsi );
        assertTrue( bsi instanceof Interaction2xmlPSI2 );
    }

    public void testGetInstancePsi25() {

        UserSessionDownload session = new UserSessionDownload( PsiVersion.getVersion25() );

        Interaction2xmlI bsi = Interaction2xmlFactory.getInstance( session );

        assertNotNull( bsi );
        assertTrue( bsi instanceof Interaction2xmlPSI25 );
    }
}