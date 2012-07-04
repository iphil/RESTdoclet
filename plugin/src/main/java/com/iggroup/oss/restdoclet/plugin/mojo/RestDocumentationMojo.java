/*
 * #%L restdoc-plugin %% Copyright (C) 2012 IG Group %% Licensed under the
 * Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable
 * law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License. #L%
 */
package com.iggroup.oss.restdoclet.plugin.mojo;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Logger;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.jibx.runtime.JiBXException;

import com.iggroup.oss.restdoclet.doclet.type.Controller;
import com.iggroup.oss.restdoclet.doclet.type.ControllerSummary;
import com.iggroup.oss.restdoclet.doclet.type.Method;
import com.iggroup.oss.restdoclet.doclet.type.Service;
import com.iggroup.oss.restdoclet.doclet.type.Services;
import com.iggroup.oss.restdoclet.doclet.type.Uri;
import com.iggroup.oss.restdoclet.doclet.util.ControllerTypePredicate;
import com.iggroup.oss.restdoclet.doclet.util.DocletUtils;
import com.iggroup.oss.restdoclet.doclet.util.JiBXUtils;
import com.iggroup.oss.restdoclet.plugin.io.ConfigCopier;
import com.iggroup.oss.restdoclet.plugin.io.DirectoryBuilder;
import com.iggroup.oss.restdoclet.plugin.io.JarBuilder;
import com.iggroup.oss.restdoclet.plugin.util.JavadocNotFoundException;
import com.iggroup.oss.restdoclet.plugin.util.MavenUtils;
import com.iggroup.oss.restdoclet.plugin.util.ServiceUtils;

/**
 * Mojo for generating services from Java documentation created by XmlDoclet.
 * 
 * @goal restdoclet
 * @phase package
 * @requiresProject
 */
public class RestDocumentationMojo extends AbstractMojo {

   private static final Logger LOG = Logger
      .getLogger(RestDocumentationMojo.class);

   /**
    * The artifact identifier of the module this Mojo is running on.
    * 
    * @parameter expression="${project.artifactId}"
    * @readonly
    * @required
    */
   private transient String artifactId;

   /**
    * The version of the module this Mojo is running on.
    * 
    * @parameter expression="${project.version}"
    * @readonly
    * @required
    */
   private transient String version;

   /**
    * The packaging of the module this Mojo is running on.
    * 
    * @parameter expression="${project.packaging}"
    * @readonly
    * @required
    */
   private transient String packaging;

   /**
    * The build-name of the module this Mojo is running on.
    * 
    * @parameter expression="${project.build.finalName}"
    * @readonly
    * @required
    */
   private transient String finalName;

   /**
    * The base-directory of the module this Mojo is running on.
    * 
    * @parameter expression="${basedir}"
    * @readonly
    * @optional
    */
   private transient File baseDirectory;

   /**
    * The classifier used by documentation. It is set to <code>restdoclet</code>
    * by default.
    * 
    * @parameter default-value="restdoclet"
    * @readonly
    */
   private transient String classifier;

   /**
    * The name of the the directory this Mojo's output is generated.
    * 
    * @parameter expression="${outputDirectory}" default-value="restdoclet"
    * @readonly
    */
   private transient String outputDirectory;

   /**
    * The scm url of the module this Mojo is running on.
    * 
    * @parameter expression="${project.scm.url}"
    * @readonly
    * @required
    */
   private transient String scmUrl;

   /**
    * The list of parameters that have to be excluded while matching URLs
    * defined in <code>RESTURLTreeHandlerMapping</code> (or
    * <code>RESTURLHandlerMapping</code>) and the methods in controllers.
    * 
    * @parameter
    */
   private transient List<String> excludes;

   /**
    * The documentation of controllers generated by XmlDoclet.
    */
   private final transient Collection<Controller> controllers =
      new ArrayList<Controller>();

   /**
    * Collects documentations of controllers and data-binders.
    * 
    * @throws CloneNotSupportedException if a data-binder's documentation can't
    *            be cloned.
    * @throws FileNotFoundException if the file containing documentation can't
    *            be found.
    * @throws JiBXException if a JiBX exception occurs.
    */
   private void javadocs() throws CloneNotSupportedException,
   FileNotFoundException, JiBXException {
      LOG.info("Collecting controller javadocs");
      /* root directory */
      File root = baseDirectory;
      while (root.getParentFile() != null
         && new File(root.getParentFile(), MavenUtils.POM_FILE).exists()) {
         root = root.getParentFile();
      }
      /* collect controller javadocs */
      LOG.info("Collecting Controller javadocs");
      final Collection<File> cfiles =
         ServiceUtils.collectControllerJavadocs(root);
      if (cfiles.size() == 0) {
         throw new IllegalArgumentException(
            "No controller javadoc found.  Is the javadoc plugin configured correctly?");
      }
      for (final File file : cfiles) {
         LOG.debug(file.getAbsolutePath() + File.separatorChar
            + file.getName());
         final Controller cntrl = JiBXUtils.unmarshallController(file);
         LOG.info(cntrl.getType());
         for (Method m : cntrl.getMethods()) {
            LOG.info(m.toString());
         }
         if (!controllers.contains(cntrl)) {
            controllers.add(cntrl);
         }
      }
   }

   /**
    * Generates services from the documentation of controllers and
    * data-binders.
    * 
    * @throws BeansNotFoundException if a bean with an identifier or Java type
    *            can't be found.
    * @throws IOException if services can't be marshaled.
    * @throws JavadocNotFoundException if a controller's documentation can't be
    *            found.
    * @throws JiBXException if a JiBX exception occurs.
    */
   private void services() throws IOException, JavadocNotFoundException,
   JiBXException {
      LOG.info("Generating services");
      DirectoryBuilder dirs =
         new DirectoryBuilder(baseDirectory, outputDirectory);

      int identifier = 1;
      List<Service> services = new ArrayList<Service>();

      LOG.info("Looking for mappings");
      HashMap<String, ArrayList<Method>> uriMethodMappings =
         new HashMap<String, ArrayList<Method>>();
      HashMap<String, Controller> uriControllerMappings =
         new HashMap<String, Controller>();
      HashMap<String, Collection<Uri>> multiUriMappings =
         new HashMap<String, Collection<Uri>>();
      for (Controller controller : controllers) {
         LOG.info(new StringBuilder().append("- Controller ")
            .append(controller.getType()).toString());
         for (Method method : controller.getMethods()) {
            LOG.info(new StringBuilder().append("... for Method ").append(
               method.toString()));

            if (excludeMethod(method)) {
               continue;
            }

            // Collate multiple uris into one string key.
            Collection<Uri> uris = method.getUris();
            if (!uris.isEmpty()) {
               String multiUri = "";
               for (Uri uri : uris) {
                  multiUri = multiUri + ", " + uri;
               }

               multiUriMappings.put(multiUri, uris);
               ArrayList<Method> methodList = uriMethodMappings.get(multiUri);
               if (methodList == null) {
                  methodList = new ArrayList<Method>();
                  uriMethodMappings.put(multiUri, methodList);
               }
               methodList.add(method);
               uriControllerMappings.put(multiUri, controller);
            }
         }

      }

      LOG.info("Processing controllers...");
      for (String uri : uriControllerMappings.keySet()) {
         LOG.info(new StringBuilder().append("Processing controllers for ")
            .append(uri).toString());
         Controller controller = uriControllerMappings.get(uri);
         LOG.info(new StringBuilder().append("Found controller ")
            .append(uriControllerMappings.get(uri).getType()).toString());
         ArrayList<Method> matches = uriMethodMappings.get(uri);
         LOG.info(new StringBuilder().append("Found methods ")
            .append(matches.toString()).append(" ").append(matches.size())
            .toString());

         Service service =
            new Service(identifier, multiUriMappings.get(uri), new Controller(
               controller.getType(), controller.getJavadoc(), matches));
         services.add(service);
         service.assertValid();
         JiBXUtils.marshallService(service,
            ServiceUtils.serviceFile(dirs, identifier));
         identifier++;
      }

      LOG.info("Processing services...");
      Services list = new Services();
      for (Service service : services) {
         org.apache.commons.collections.Predicate predicate =
            new ControllerTypePredicate(service.getController().getType());
         if (CollectionUtils.exists(list.getControllers(), predicate)) {
            ControllerSummary controller =
               (ControllerSummary) CollectionUtils.find(list.getControllers(),
                  predicate);
            controller.addService(service);
         } else {
            ControllerSummary controller =
               new ControllerSummary(service.getController().getType(),
                  service.getController().getJavadoc());
            controller.addService(service);
            list.addController(controller);
         }
      }

      LOG.info("Marshalling services...");
      list.assertValid();
      JiBXUtils.marshallServices(list, ServiceUtils.servicesFile(dirs));
   }

   /**
    * Generates the web-application.
    * 
    * @throws IOException if an input-output exception occurs.
    */
   private void jar() throws IOException {
      LOG.info("Generating jar-archive");
      final DirectoryBuilder dirs =
         new DirectoryBuilder(baseDirectory, outputDirectory);
      final ConfigCopier cc = new ConfigCopier(dirs);
      cc.copy();

      LOG.debug("Creating properties: " + artifactId + ", " + version + ", "
         + finalName + ", " + classifier + ", " + scmUrl);
      cc.createProperties(artifactId, version, finalName, classifier, scmUrl);
      LOG.debug("Building jar: " + finalName + '-' + classifier);
      new JarBuilder(dirs, finalName + '-' + classifier).build();
   }

   /**
    * Facade method for generating services and the web-application.
    * 
    * @throws BeansNotFoundException if a bean with an identifier or Java type
    *            can't be found.
    * @throws CloneNotSupportedException if a data-binder's documentation can't
    *            be cloned.
    * @throws IOException if an input-output exception occurs.
    * @throws JavadocNotFoundException if a controller's documentation can't be
    *            found.
    * @throws JiBXException if a JiBX exception occurs.
    */
   private void build() throws CloneNotSupportedException, IOException,
   JavadocNotFoundException, JiBXException {
      /* javadocs */
      javadocs();
      /* services */
      services();
      /* web */
      jar();
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public void execute() throws MojoExecutionException {

      try {

         DocletUtils.initialiseLogging();

         if (MavenUtils.WAR_PACKAGING.equalsIgnoreCase(packaging)) {
            build();
         }
      } catch (final IOException e) {
         throw new MojoExecutionException(e.getClass().getName() + ": "
            + e.getMessage(), e);
      } catch (final JiBXException e) {
         throw new MojoExecutionException(e.getClass().getName() + ": "
            + e.getMessage(), e);
      } catch (final JavadocNotFoundException e) {
         throw new MojoExecutionException(e.getClass().getName() + ": "
            + e.getMessage(), e);
      } catch (final CloneNotSupportedException e) {
         throw new MojoExecutionException(e.getClass().getName() + ": "
            + e.getMessage(), e);
      } catch (final Exception e) {
         throw new MojoExecutionException(e.getClass().getName() + ": "
            + e.getMessage(), e);
      }

   }

   /**
    * Return true if the method name is on the exclude list
    * 
    * @param method
    * @return true if the method name is on the exclude list
    */
   private boolean excludeMethod(Method method) {
      for (String exclude : excludes) {
         if (method.getName().equalsIgnoreCase(exclude)) {
            return true;
         }
      }
      return false;
   }

}