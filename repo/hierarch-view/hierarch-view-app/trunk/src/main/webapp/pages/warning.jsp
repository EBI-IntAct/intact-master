<!--
   - Copyright (c) 2002 The European Bioinformatics Institute, and others.
   - All rights reserved. Please see the file LICENSE
   - in the root directory of this distribution.
   -
   - hierarchView warning page
   - This should be displayed in the content part of the IntAct layout,
   - it report any problems which have occured during the processing
   -
   - @author Samuel Kerrien (skerrien@ebi.ac.uk)
   - @version $Id$
-->

<%--
  - Displays message stored in the struts framework.
  --%>

<%@ page language="java" %>
<%@ taglib uri="/WEB-INF/tld/struts-html.tld"  prefix="html" %>
<%@ taglib uri="/WEB-INF/tld/struts-bean.tld"  prefix="bean" %>
<%@ taglib uri="/WEB-INF/tld/struts-logic.tld" prefix="logic" %>
<%@ taglib uri="/WEB-INF/tld/intact.tld"       prefix="intact" %>

<%-- restore eventual errors inorder to be displayed --%>
<intact:restoreErrors/>

<logic:messagesPresent message="true">

    <strong><font color="orange">Warning,</font></strong>
    be aware that some problems occured during the processing of your request:
    <ul>
         <html:messages id="message" message="true">
            <%-- If the filter is false, it prevent bean:write to convert HTML to text --%>
            <li><bean:write name="message" filter="false" /></li>
         </html:messages>
    </ul>

    </hr>

</logic:messagesPresent>

<%-- Clear errors to avoid their accumulation --%>
<intact:clearErrors/>

