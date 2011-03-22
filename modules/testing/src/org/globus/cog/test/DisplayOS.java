
// ----------------------------------------------------------------------
// This code is developed as part of the Java CoG Kit project
// The terms of the license can be found at http://www.cogkit.org/license
// This message may not be removed or altered.
// ----------------------------------------------------------------------

package org.globus.cog.test;

public class DisplayOS extends AbstractTest {

    public void test(String machine) {
        output.printField(machines.getOS(MachineListParser.getHost(machine)));
    }

    public String getServiceName() {
        return null;
    }

    public String getColumnName() {
        return "OS";
    }

}