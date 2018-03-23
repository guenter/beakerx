/*
 *  Copyright 2017 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.twosigma.beakerx.groovy.evaluator;

import com.twosigma.beakerx.NamespaceClient;
import com.twosigma.beakerx.TryResult;
import com.twosigma.beakerx.evaluator.Evaluator;
import com.twosigma.beakerx.evaluator.InternalVariable;
import com.twosigma.beakerx.jvm.object.SimpleEvaluationObject;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.StackTraceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.concurrent.Callable;

import static com.twosigma.beakerx.evaluator.BaseEvaluator.INTERUPTED_MSG;
import static com.twosigma.beakerx.groovy.evaluator.GroovyStackTracePrettyPrinter.printStacktrace;

class GroovyCodeRunner implements Callable<TryResult> {

  private static final Logger logger = LoggerFactory.getLogger(GroovyCodeRunner.class.getName());
  public static final String SCRIPT_NAME = "script";
  private GroovyEvaluator groovyEvaluator;
  private final String theCode;
  private final SimpleEvaluationObject theOutput;

  public GroovyCodeRunner(GroovyEvaluator groovyEvaluator, String code, SimpleEvaluationObject out) {
    this.groovyEvaluator = groovyEvaluator;
    theCode = code;
    theOutput = out;
  }

  @Override
  public TryResult call() throws Exception {
    ClassLoader oldld = Thread.currentThread().getContextClassLoader();
    TryResult either;
    String scriptName = SCRIPT_NAME;
    try {
      Object result = null;
      theOutput.setOutputHandler();
      Thread.currentThread().setContextClassLoader(groovyEvaluator.getGroovyClassLoader());

      scriptName += System.currentTimeMillis();
      Class<?> parsedClass = groovyEvaluator.getGroovyClassLoader().parseClass(theCode, scriptName);

      Script instance = (Script) parsedClass.newInstance();

      if (GroovyEvaluator.LOCAL_DEV) {
        groovyEvaluator.getScriptBinding().setVariable(Evaluator.BEAKER_VARIABLE_NAME, new HashMap<String, Object>());
      } else {
        groovyEvaluator.getScriptBinding().setVariable(Evaluator.BEAKER_VARIABLE_NAME, NamespaceClient.getBeaker(groovyEvaluator.getSessionId()));
      }

      instance.setBinding(groovyEvaluator.getScriptBinding());

      InternalVariable.setValue(theOutput);

      result = instance.run();

      if (GroovyEvaluator.LOCAL_DEV) {
        logger.info("Result: {}", result);
        logger.info("Variables: {}", groovyEvaluator.getScriptBinding().getVariables());
      }
      either = TryResult.createResult(result);
    } catch (Throwable e) {
      if (GroovyEvaluator.LOCAL_DEV) {
        logger.warn(e.getMessage());
        e.printStackTrace();
      }

      //unwrap ITE
      if (e instanceof InvocationTargetException) {
        e = ((InvocationTargetException) e).getTargetException();
      }

      if (e instanceof InterruptedException || e instanceof InvocationTargetException || e instanceof ThreadDeath) {
        either = TryResult.createError(INTERUPTED_MSG);
      } else {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        StackTraceUtils.sanitize(e).printStackTrace(pw);
        String value = sw.toString();
        value = printStacktrace(scriptName, value);
        either = TryResult.createError(value);
      }
    } finally {
      theOutput.clrOutputHandler();
      Thread.currentThread().setContextClassLoader(oldld);
    }
    return either;
  }

}
