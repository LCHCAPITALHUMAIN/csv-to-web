/**

	This is an example how to create a simple chemical compound database using:
	
	- Groovy (http://groovy.codehaus.org)
	- Gradle (http://www.gradle.org
	- Ratpack (https://github.com/bleedingwolf/Ratpack)
	- GMongo (https://github.com/poiati/gmongo)
	- MongoDB (http://www.mongodb.org)
	
	 ____  ____  ____  ____  ____  ____ 
	||S ||||i ||||m ||||p ||||l ||||e ||
	|/__\||/__\||/__\||/__\||/__\||/__\|
		 ____  ____  ____  ____  ____  ____  ____  ____ 
		||C ||||h ||||e ||||m ||||i ||||c ||||a ||||l ||
		|/__\||/__\||/__\||/__\||/__\||/__\||/__\||/__\|
			 ____  ____  ____  ____  ____  ____  ____  ____ 
			||C ||||o ||||m ||||p ||||o ||||u ||||n ||||d ||
			|/__\||/__\||/__\||/__\||/__\||/__\||/__\||/__\|
				 ____  ____  ____  ____  ____  ____  ____  ____ 
				||D ||||a ||||t ||||a ||||b ||||a ||||s ||||e ||
				|/__\||/__\||/__\||/__\||/__\||/__\||/__\||/__\|
	
	Copyright 2012 Michael van Vliet, Netherlands Metabolomics Centre (NMC) and Netherlands Bioinformatics Centre (NBIC) 

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

		http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.	
 **/
  
  
// imports
@Grab('com.gmongo:gmongo:0.8')
@Grab('commons-fileupload:commons-fileupload:1.2.2')
@Grab('commons-io:commons-io:1.3.2')
import com.gmongo.GMongo 
import org.apache.commons.fileupload.servlet.ServletFileUpload
import org.apache.commons.fileupload.disk.DiskFileItemFactory
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import java.util.Random


// config
def mdbHost				= 'localhost' // host where mongoDB is running
def mdbPort				= 27017 // port where mongoDB listens
def mdbDatabase			= 'simpleCompoundDatabase' // name of the database
def bootstrap			= false // true/false
def clearAtStartup		= true //true/false
def rpPort				= 8080 //define the (Ratpack) http port to run on
def exportsFolder		= 'exports'
def reservedProperties	= ['_id','cid','created','modified']
 
 
// connect to the database
def gmongo	= new GMongo	("${mdbHost}:${mdbPort}")
def db 		= gmongo.getDB	("${mdbDatabase}")

//check if we need to clear the database before starting
if (clearAtStartup == true){
	db.compounds.drop() //clear old stuff
}

// bootstrap
if (bootstrap){
	def rand  = new Random()

	5.times { cid ->
		def elements = 'C' + rand.nextInt(7) + 'H' + rand.nextInt(7) + 'O' + rand.nextInt(7)
		upsertCompound(db, [
			InChI: 'InChI=1S/' + elements + '/' + cid, 
			elements: elements, 
			contributor: 'myEmployer',
			owner: 'me',
			database: mdbDatabase, 
			host: mdbHost	]
		)
	}
}

// define Ratpack settings
set 'templateRoot', 'templates'
set 'port', rpPort

// homepage
get("/") { render "index.html", [compoundCount: db.compounds.find().size()] }

// the a list of all compounds in the database
get("/list") { respond(db.compounds.find()) }

// a single (full details) compound by ID
get("/compound/:cid") { respond(findCompoundByCid(db, urlparams.cid as int)) }

// a list of compounds by Elemental Composition  (uses regular expressions)
get("/elements/:elements") { respond(findCompoundByElements(db, urlparams.elements as String)) }

// search page
get("/search") { render "search.html", [templateVars: ['headers': findAllHeaders(db, reservedProperties)]] }

register(["get", "post"], "/search/:method") {

	def templateVars = [:]
	def response

	switch (urlparams.method){
		case 'listAllCompounds'		:	response = findAllCompounds(db); break;
		case 'searchByCid'			:	if (params.cid){ response = findCompoundByCid(db, params.cid as int) }; break;
		default						:	def label = urlparams.method.split('_')[1]
										response = findCompoundByLabel(db, label, params."${label}" as String);										
	}
	
	// prepare the variables for the template
	templateVars[urlparams.method] 		= response
	templateVars['urlparams'] 			= urlparams
	templateVars['params'] 				= params
	templateVars['headers']				= findAllHeaders(db, reservedProperties)
	
	render "search.html", [templateVars: templateVars]
}

// add compounds page
get("/import") { render "import.html" }

post("/import") {

	boolean isMultipart = ServletFileUpload.isMultipartContent(request)
	if (isMultipart) {
		// Create a factory for disk-based file items
		def factory = new DiskFileItemFactory()

		// Create a new file upload handler
		def upload = new ServletFileUpload(factory)

		// Parse the request
		List files = upload.parseRequest(request)
		
		// Merge file(s)
		def compoundData = ''	
		files.each { compoundData += it.getString() }
		
		// Create lists with the lines and read the header
		def lines = compoundData.replaceAll('\r\n','\n').replaceAll('\r','\n').split('\n')
		def header = lines[0].split("\t")
		
		// iterate over the lines from the file to import
		lines.each { line ->
				
			//init empty compound
			def compound = [:]
			
			line.split("\t").eachWithIndex { rowValue, columnIndex ->
				if (header[columnIndex] != rowValue){ // make sure we skip the header when importing
					
					//see if we have to trim the value
					if (rowValue[0] == '"' && rowValue[-1] == '"'){
						rowValue = rowValue[1..-2] // trim the first and last "
					}
					
					compound[header[columnIndex]] = rowValue
				}
			}
			
			if (compound != [:]){		
				//update or insert this compound
				upsertCompound(db, compound)
			}
				
		}		
	}

	render "import.html" 
}

// register exportsFolder
get("/" + exportsFolder + "/:file"){
	return new File(exportsFolder + '/' + urlparams.file).text
}
	
//export compounds
get("/export") {
	
	if (params.doExport){
	
		//fetch all compounds
		def compounds = formatResponse(db.compounds.find())

		//prepare the headers
		def headers = findAllHeaders(db, reservedProperties).sort { a,b -> a <=> b}
	
		// add the header to the CSV
		def csvOut = "cid\t" + headers.join("\t") + "\n"

		//iterate over compounds
		compounds['results'].each { compound ->
		
			csvOut += compound.cid
		
			//iterate over all available headers
			headers.each { header ->			
				csvOut +=  "\t" + compound."${header}"
			}
			
			csvOut +=  "\n"
		}
		
		//write export to file
		new File(exportsFolder).mkdirs()
		new File(exportsFolder + '/export.' +  new Date().time + '.csv') << csvOut		
	}	
	
	render "export.html", [exportDirectory: new File(exportsFolder)] 
}

/**
	Methods for fetching data, data manipulation and fetch response
 **/

// prepare and send the actual response
private respond(response){ 
	return new groovy.json.JsonBuilder( formatResponse(response) ).toPrettyString()
}

// define a uniform layout of the response
private formatResponse(result) {
	return ['results': result.collect { it.findAll { it.key != '_id' } }.sort { a,b -> a.id <=> b.id}, 'count': result.size()]
}

// find all compounds
private findAllCompounds(db){
	return db.compounds.find()
}

// find compound by id
private findCompoundByCid(db, int cid){
	return db.compounds.find(cid: cid)
}

// find compounds by a label
private findCompoundByLabel(db, String label, String labelValue){
	
	def findHash = [:]
		findHash["${label}"] = ~"${labelValue}"
	
	return db.compounds.find(findHash)
}

private findAllHeaders(db, headersToSkip = []){

	//prepare the headers
	def headers = [].toList()
	db.compounds.find().each {
		headers = it.keySet().toList() + headers
	}
	
	return headers.unique().findAll { headersToSkip.count(it) != 1 }.sort { a,b -> a <=> b}
}

// insert or update a compound
private upsertCompound(db, HashMap compound){
	
	//some characters in property names give problems retrieving data, we force labels to CamelCase when it has a space, - or a _
	def tempCompound = [:]
	compound.each { propertyKey, propertyValue ->
		tempCompound[toCamelCase(propertyKey)] = propertyValue
	}
	compound = tempCompound
	
	//there are some reserved compound properties (e.g cid, created, modified)
	try {
		// if we cannot find a compound with this id, we set the created to match the modified property
		if (compound['cid'] == null || !findCompoundByCid(db, compound['cid'])){
			//TODO: make this look for the highest CID and increment this with one, the way we do it now only works because we start CID with 0
			compound['cid']		= (db.compounds.find().size() ?: 0) as int //force the CID to auto-increment 
			compound['created'] = new Date().time
		} 
		
		// update the modified timestamp to track when changes are made to the compound
		compound['modified'] = new Date().time
		
		// send changes to the database
		db.compounds.update([cid: compound['cid']], [$set: compound], true)
	} catch(e) {
		println 'Error saving the compound: ' + e
		return false
	}	
	
	return true
}

private toCamelCase(String label){
	label = label.replaceAll(/(\w)(\w*)/) { wholeMatch, initialLetter, restOfWord -> initialLetter.toUpperCase() + restOfWord }
	label = label.split('_').collect { it[0].toUpperCase() + it.substring(1) }.join('')
	label = label.split('-').collect { it[0].toUpperCase() + it.substring(1) }.join('')
	
	return label
}
