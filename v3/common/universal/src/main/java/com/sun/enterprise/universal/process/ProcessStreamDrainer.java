/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

/*
 * ProcessStreamDrainer.java
 *
 * Created on October 26, 2006, 9:56 PM
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

package com.sun.enterprise.universal.process;

import java.io.*;
import java.util.*;

/**
 * If you don't drain a process' stdout and stderr it will cause a deadlock after a few hundred bytes of output.
 * At that point the Process is blocked because its stdout and/or stderr buffer is full and it is waiting for the Java caller
 * to drain it.  Meanwhile the Java program is blocked waiting on the external process.
 * This class makes this common, but messy and tricky, procedure easier.
 * It creates 2 threads that drain output on stdout and stderr of the external process.
 * <p> Sample Code:
 *
 * <pre>
 * ProcessBuilder pb = new ProcessBuilder("ls",  "-R", "c:/as");
 * try
 * {
 *      Process p = pb.start();
 *      ProcessStreamDrainer psd = ProcessStreamDrainer.drain("MyProcess", p);
 *      // or
 *      ProcessStreamDrainer psd = ProcessStreamDrainer.redirect("MyProcess", p);
 *      psd.waitFor(); // this is optional.
 * }
 * catch (Exception ex)
 * {
 *      ex.printStackTrace();
 * }
 * </pre>
 *
 * @author bnevins
 */
public class ProcessStreamDrainer
{
    /**
     * Create an instance and drain the process' stderr and stdout
     * @param process The Process to drain
     * @param processName The name will be used to name the drainer threads
     */
    public static ProcessStreamDrainer drain(String processName, Process process)
    {
        ProcessStreamDrainer psd = new ProcessStreamDrainer(processName, process, false);
        psd.drain();
        return psd;
    }

    /**
     * Create an instance, drain and redirect the process' stderr and stdout to 
     * System.err and System.out respectively.
     * @param process The Process to drain
     * @param processName The name will be used to name the drainer threads
     */
    public static ProcessStreamDrainer redirect(String processName, Process process)
    {
        ProcessStreamDrainer psd = new ProcessStreamDrainer(processName, process, true);
        psd.drain();
        return psd;
    }

    /**
     * Wait for the drain threads to die.  This is guaranteed to occur after the
     * external process dies.  Note that this may, of course, block indefinitely.
     */
    public final void waitFor() throws InterruptedException
    {
        errThread.join();
        outThread.join();
    }
    
    ///////////////////////////////////////////////////////////////////////////
    
    private ProcessStreamDrainer(String processName, Process process, boolean redirect)
    {
        if(process == null)
            throw new NullPointerException("Internal Error: null Process object");
        
        this.process = process;
        
        if(processName == null || processName.length() <= 0)
            this.processName = "UnknownProcessName";
        else
            this.processName = processName;
        
        redirectStandardStreams = redirect;

        ProcessStreamDrainerWorker worker;
        
        if(redirectStandardStreams)
            worker = new ProcessStreamDrainerWorker(process.getInputStream(), System.out);
        else
            worker = new ProcessStreamDrainerWorker(process.getInputStream());

        outThread = new Thread(worker, processName + "-" + OUT_DRAINER);
        outThread.setDaemon(true);
        
        if(redirectStandardStreams)
            worker = new ProcessStreamDrainerWorker(process.getErrorStream(), System.err);
        else
            worker = new ProcessStreamDrainerWorker(process.getErrorStream());
        
        errThread = new Thread(worker, processName + "-" + ERROR_DRAINER);
        errThread.setDaemon(true);
    }
    
    /**
     * Start the draining.
     * We start them here instead of the constructor so that "this" doesn't 
     * leak out of the constructor.
     */
    private void drain()
    {
        outThread.start();
        errThread.start();
    }
    
    ///////////////////////////////////////////////////////////////////////////
    
    private final           Process                     process;
    private final           Thread                      errThread;
    private final           Thread                      outThread;
    private final           String                      processName;
    private final           boolean                     redirectStandardStreams;
    private final   static  String                      ERROR_DRAINER   = "StderrDrainer";
    private final   static  String                      OUT_DRAINER     = "StdoutDrainer";
    
    ///////////////////////////////////////////////////////////////////////////

}

