/*

   Derby - Class com.splicemachine.db.iapi.sql.compile.Visitable

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package com.splicemachine.db.iapi.sql.compile;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.impl.sql.compile.QueryTreeNode;

/**
 * A Visitable is something that can be visited by a Visitor
 */
public interface Visitable {

    /**
     * Accept a visitor, and call visitor.visit(Visitable node) on child nodes as necessary.
     *
     * @param visitor the visitor
     */
    Visitable accept(Visitor visitor) throws StandardException;

    /**
     * Accept a visitor, and call v.visit(Visitable node, parentNode) on child nodes as necessary.
     *
     * @param visitor the visitor
     * @param parent the parent node of the node upon which this method is invoked, can be null if none or unknown.
     */
    Visitable accept(Visitor visitor, QueryTreeNode parent) throws StandardException;


}